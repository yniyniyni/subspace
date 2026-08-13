// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.failure
import art.yniyniyni.subspace.core.xray.ConfigResult
import art.yniyniyni.subspace.core.xray.LibXrayPingApi
import art.yniyniyni.subspace.core.xray.ProxyHeadProbe
import art.yniyniyni.subspace.core.xray.SocketProtector
import art.yniyniyni.subspace.core.xray.TcpProbe
import art.yniyniyni.subspace.core.xray.TcpSocketProtector
import art.yniyniyni.subspace.core.xray.TunnelSettings
import art.yniyniyni.subspace.core.xray.XrayConfigGenerator
import art.yniyniyni.subspace.core.xray.XrayController
import art.yniyniyni.subspace.core.xray.XrayException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "TunnelService"

private const val TUN_ADDRESS = "10.7.0.1"
private const val TUN_PREFIX = 30

// §5.2: without an IPv6 address and route, every AAAA-answered lookup and every
// IPv6-capable socket leaves outside the tunnel on a dual-stack network. That is
// the same partial-leak shape §5.2 warns about — it passes a leak test on an
// IPv4-only Wi-Fi and fails on carrier IPv6.
private const val TUN_ADDRESS_V6 = "fd00:1:2:3::1"
private const val TUN_PREFIX_V6 = 126

private const val TUN_MTU = 8500
private const val DNS_SERVER = "1.1.1.1"

/**
 * Owns the tunnel.
 *
 * Runs in `:bg` (§3). Nothing here may be reached from `:main` except through
 * [ITunnelService] — the processes share no memory, so a Hilt singleton or an
 * `object` is two different instances.
 *
 * ## Concurrency
 *
 * §5.4 says teardown is reachable from three places that are **not** serialised
 * with each other: `disconnect()` and `onRevoke()` arrive on binder threads,
 * `onDestroy()` on the main thread, and the start sequence runs on IO. So:
 *
 *  - [lock] guards every field below and every state publication. `RemoteCallbackList`
 *    is not safe for concurrent broadcast — `beginBroadcast()` throws if one is
 *    already in progress, and that throw landing inside teardown would abandon
 *    the TUN fd, which is §5.4's wedged-until-reboot outcome.
 *  - [generation] supersedes an in-flight start. Coroutine cancellation is
 *    cooperative and the tail of the start sequence has no suspension points, so
 *    `cancel()` alone cannot stop it from publishing `Connected` after a teardown
 *    published `Disconnected` — §5.5's lying UI, reachable by two taps.
 *  - Slow teardown work runs **outside** [lock], so a wedged `quit()` cannot
 *    block state publication forever.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class TunnelService : VpnService() {
    // Field injection, not constructor injection: VpnService (like every Android
    // component) is instantiated by the platform, not by Hilt. Available once
    // super.onCreate() has run — Hilt_TunnelService.onCreate() injects before
    // delegating up to the real Service.onCreate().
    @Inject
    lateinit var profileRepository: ProfileRepository

    @Inject
    lateinit var routingRepository: RoutingRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var geoAssetRepository: GeoAssetRepository

    // Built in onCreate, once profileRepository is injected. Wraps the
    // repository's two spec-D4 methods as plain suspend lambdas rather than
    // handing ConnectionRecorder the repository itself — see ConnectionRecorder's
    // KDoc for why that indirection is what keeps it unit-testable.
    private lateinit var connectionRecorder: ConnectionRecorder

    // Built in onCreate, once the three repositories above are injected. Same
    // suspend-lambda indirection as connectionRecorder, for the same reason —
    // see RoutingResolver's KDoc.
    private lateinit var routingResolver: RoutingResolver

    private val errorHandler =
        CoroutineExceptionHandler { _, e ->
            // §10.4: anything escaping the start sequence must still produce a
            // legible state. Without this the process dies mid-start and the UI
            // sits on Connecting until it notices binder death.
            Log.e(TAG, "start sequence crashed: ${e.javaClass.simpleName}")
            publish(failure(FailureReason.CoreStartFailed, e.javaClass.simpleName))
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private val callbacks = RemoteCallbackList<ITunnelCallback>()

    /** Guards measurement against another app's VPN holding the route — see [ForeignVpn]. */
    private val foreignVpn by lazy { ForeignVpn(applicationContext) }

    /**
     * Latency measurement lives in `:bg` because §5.1's protector does.
     *
     * `by lazy` rather than an initialiser: it captures [scope] and reaches
     * [profileRepository], which Hilt field-injects during `super.onCreate()`.
     * A measurement run is also the one thing this service does that needs no
     * TUN, no VPN permission and no foreground notification — `onBind` hands out
     * this binder for any action but `SERVICE_INTERFACE`, so `:main` can bind,
     * measure, and unbind while disconnected.
     */
    private val latencyRunner by lazy {
        LatencyRunner<StoredProfile>(
            measure = { profile, options -> measureOne(profile, options) },
            loadProfile = { id -> profileRepository.profile(id) },
            scope = scope,
            // §10.4: a swallowed failure still gets a line. The class name only —
            // a Room or libXray message can quote a stored config value (§5.6).
            onMeasurementError = { name -> Log.w(TAG, "measurement failed: $name") },
        )
    }

    private val lock = Any()

    // All guarded by `lock`.
    private var controller: XrayController? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var configFile: File? = null
    private var generation = 0
    private var currentState: ConnectionState = ConnectionState.Disconnected

    /**
     * Commits a terminal outcome in an order teardown cannot interleave with — see
     * [TerminalOutcome] for the race this closes and why it is a separate class.
     *
     * Declared **after** [lock], not with the other collaborators above it: property
     * initialisers run in declaration order, so constructing this before `lock = Any()` would
     * capture null and every `synchronized` inside it would guard nothing.
     */
    private val terminalOutcome =
        TerminalOutcome(
            lock = lock,
            currentGeneration = { generation },
            publish = { state -> publishLocked(state) },
        )

    override fun onCreate() {
        super.onCreate()
        TunnelNotification.ensureChannel(this)
        // §5.6: a config left by a start that failed, or by a process the system
        // killed before onDestroy, holds the UUID and REALITY key. Nothing else
        // would ever remove it.
        File(filesDir, CONFIG_NAME).delete()
        connectionRecorder =
            ConnectionRecorder(
                recordConnected = profileRepository::recordConnected,
                recordError = profileRepository::recordError,
                onFailure = { e ->
                    // §5.6: never the exception message — Room/SQLite errors can
                    // quote back the value that failed to write.
                    Log.e(TAG, "failed to record connection outcome: ${e.javaClass.simpleName}")
                },
            )
        routingResolver =
            RoutingResolver(
                activeRuleSetId = { settingsRepository.activeRoutingRuleSetId.first() },
                loadRuleSet = { id -> routingRepository.ruleSet(id) },
                installedGeoFiles = { geoAssetRepository.installedFileNames() },
            )
    }

    /**
     * §11 and §5.4: a started service must not be resurrected with a null intent
     * after a kill, and it must not linger once the tunnel is down.
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    // ── State publication ───────────────────────────────────────────────────

    private fun publish(next: ConnectionState) {
        synchronized(lock) { publishLocked(next) }
    }

    private fun publishLocked(next: ConnectionState) {
        currentState = next
        val parcel = ConnectionStateParcel.from(next)
        val count = callbacks.beginBroadcast()
        repeat(count) { i ->
            try {
                callbacks.getBroadcastItem(i).onStateChanged(parcel)
            } catch (e: android.os.RemoteException) {
                // The UI process died mid-broadcast. RemoteCallbackList prunes
                // dead entries itself; nothing here should abort the tunnel, and
                // §5.6 forbids logging anything that might quote the config.
                Log.w(TAG, "callback dropped: ${e.javaClass.simpleName}")
            }
        }
        callbacks.finishBroadcast()
    }

    /**
     * Publishes only if this start is still the current one.
     *
     * @return false when a teardown or a newer start has superseded [gen], in
     *   which case the caller must abandon its sequence immediately.
     */
    private fun publishIfCurrent(
        gen: Int,
        next: ConnectionState,
    ): Boolean =
        synchronized(lock) {
            if (gen != generation) return false
            publishLocked(next)
            true
        }

    // ── Start sequence ──────────────────────────────────────────────────────

    /**
     * §5.3: the whole sequence is on IO, so the connect button stays live.
     * §10.4: no broad catch — every step publishes its own specific failure and
     * unwinds what it already built.
     */
    private fun startTunnel(
        profile: Profile,
        rowId: Long,
    ) {
        val gen =
            synchronized(lock) {
                // §5.5 makes this service the source of truth, so it cannot rely
                // on the UI to prevent a second connect. Without this guard the
                // previous TUN fd leaks and the old core runs on unreferenced.
                if (currentState !is ConnectionState.Disconnected &&
                    currentState !is ConnectionState.Failed
                ) {
                    Log.w(TAG, "connect ignored: a session is already active")
                    return
                }
                ++generation
            }

        // §9: go foreground BEFORE the slow work. startForegroundService
        // gives a few seconds to call startForeground or the system kills :bg —
        // and a start that fails validation never reaches the success path at all.
        goForeground(R.string.notification_connecting)

        scope.launch {
            val xray = XrayController()
            synchronized(lock) {
                if (gen != generation) return@launch
                controller = xray
            }

            // Split at the seam that matters for unwinding: once the core is up,
            // every later failure must stop it again.
            val socksPort = startCore(gen, xray, profile, rowId) ?: return@launch
            attachTun(gen, xray, socksPort, rowId)
        }
    }

    // One early return per step is the point, not a smell: §10.4 requires each
    // stage of the start sequence to fail specifically and stop there. The
    // routing gate (§4.4) added one more branch than CyclomaticComplexMethod's
    // default threshold allows.
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun startCore(
        gen: Int,
        xray: XrayController,
        profile: Profile,
        rowId: Long,
    ): Int? {
        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.AllocatingPort))) return null
        val socksPort =
            try {
                xray.allocatePort()
            } catch (e: XrayException) {
                return failStart(gen, FailureReason.PortAllocationFailed, e, rowId)
            }

        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.GeneratingConfig))) return null
        val routing =
            when (val resolution = routingResolver.resolve()) {
                is RoutingResolution.Off -> null
                is RoutingResolution.Active -> resolution.ruleSet
                is RoutingResolution.MissingGeoData ->
                    // §10.4: named specifically. The filenames are shape, not
                    // content, so they are safe to surface (§5.6).
                    return failStart(
                        gen,
                        FailureReason.GeoDataMissing,
                        IllegalStateException(resolution.missing.sorted().joinToString(", ")),
                        rowId,
                    )
            }
        val settings = TunnelSettings(socksPort, DNS_SERVER, enableSniffing = true, routing = routing)
        // §10.4/failStart's cleanup applies here too, not just to the try/catch
        // below: an early return that only published a state (skipping
        // stopForeground/stopSelf/controller = null) would leave a stuck
        // "Connecting" notification, same as every other branch in this function.
        val json =
            when (val config = XrayConfigGenerator.generate(profile, settings)) {
                is ConfigResult.Unsupported ->
                    return failStart(
                        gen,
                        FailureReason.ProtocolNotSupported,
                        IllegalArgumentException("${config.protocol} is not supported yet"),
                        rowId,
                    )

                is ConfigResult.Ok -> config.json
            }
        val file =
            try {
                writeConfig(json)
            } catch (e: java.io.IOException) {
                return failStart(gen, FailureReason.ConfigGenerationFailed, e, rowId)
            }
        synchronized(lock) {
            if (gen != generation) return null
            configFile = file
        }

        // §6: validate before starting. libXray's testXray takes a path, so the
        // bytes validated are exactly the bytes runXray will read.
        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.ValidatingConfig))) return null
        try {
            xray.validate(file)
        } catch (e: XrayException) {
            return failStart(gen, FailureReason.ConfigRejected, e, rowId)
        }

        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.StartingCore))) return null
        try {
            xray.start(file, protector())
        } catch (e: XrayException) {
            return failStart(gen, FailureReason.CoreStartFailed, e, rowId)
        }

        return socksPort
    }

    /**
     * Builds the TUN interface and hands its fd to tun2socks.
     *
     * Every failure stops the core [startCore] left running — §5.4: a
     * half-started tunnel must not survive as a leaked fd plus a live runtime.
     */
    // Same reasoning as startCore: each early return is a distinct §10.4 failure
    // or a supersede check, and collapsing them would hide which one fired.
    @Suppress("ReturnCount")
    private suspend fun attachTun(
        gen: Int,
        xray: XrayController,
        socksPort: Int,
        rowId: Long,
    ) {
        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.EstablishingTun))) return
        val fd = establishTun()
        if (fd == null) {
            xray.stop()
            failStart(
                gen,
                FailureReason.TunEstablishFailed,
                IllegalStateException("establish() returned null"),
                rowId,
            )
            return
        }
        synchronized(lock) {
            if (gen != generation) {
                // Superseded while establishing. Close what we just made rather
                // than letting teardown miss it — it never saw this fd.
                fd.close()
                return
            }
            tunInterface = fd
        }

        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.StartingTunnel))) return
        val config = tun2socksConfig(socksPort = socksPort, mtu = TUN_MTU)
        // The shim refuses a bad fd rather than aborting the process; false here
        // is a real start failure, never something to ignore.
        if (!Tun2Socks.start(config, fd.fd)) {
            xray.stop()
            synchronized(lock) {
                if (gen == generation) {
                    tunInterface = null
                }
            }
            fd.close()
            failStart(
                gen,
                FailureReason.TunnelStartFailed,
                IllegalStateException("tun2socks refused to start"),
                rowId,
            )
            return
        }

        val connected = ConnectionState.Connected(System.currentTimeMillis(), socksPort)
        // One generation-checked transition: the connected notification is established and
        // `Connected` published together under the lock, and the spec-D4 success write (see
        // ConnectionRecorder) happens strictly after. Previously this published, suspended in
        // the write, and called goForeground() on the way out — so a teardown during the
        // write left this coroutine to restore the connected notification over a tunnel that
        // was already down (§5.5). Nothing may be added after the `persist` lambda.
        terminalOutcome.settle(
            gen = gen,
            state = connected,
            lifecycle = { goForeground(R.string.notification_connected) },
            persist = { connectionRecorder.record(rowId, connected) },
        )
    }

    /**
     * Publishes a specific failure and cleans up what a failed start leaves.
     *
     * §5.6: the config file holds the UUID and REALITY key. A failed start used
     * to leave it on disk indefinitely, because only teardown deleted it.
     *
     * `suspend`, not plain: the one write `:bg` performs on failure (spec D4) runs
     * here, and it runs *after* the whole transition rather than in the middle of it.
     * `stopForeground()`/`stopSelf()` used to follow that write, so a newer connection
     * starting during it inherited this failure's teardown — its foreground state removed,
     * or its service stopped, by the previous attempt (PR #4 review, P1 finding A). Both are
     * now inside the generation-checked transition; nothing may be added after it.
     */
    private suspend fun failStart(
        gen: Int,
        reason: FailureReason,
        cause: Exception,
        rowId: Long,
    ): Nothing? {
        // failure() redacts at construction — libXray's errors quote the config
        // straight back (§5.6). Built before the transition because that is the one
        // thing here with no lifecycle effect.
        val failed = failure(reason, cause.message.orEmpty())
        terminalOutcome.settle(
            gen = gen,
            state = failed,
            lifecycle = {
                configFile?.delete()
                configFile = null
                controller = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            },
            persist = { connectionRecorder.record(rowId, failed) },
        )
        return null
    }

    /**
     * §5.1. Wired on every start, never cached: libXray's dialer controller is
     * process-global Go state, and `XrayController` clears its target on stop so
     * a destroyed service is not reachable from native code.
     */
    private fun protector() =
        SocketProtector { fd ->
            val ok = protect(fd)
            if (!ok) {
                // libXray DISCARDS this result (docs/agent/research/libxray-api.md
                // §2), so nothing upstream reports it. Without this line a failed
                // protect is invisible and presents only as §5.1's symptom:
                // connected, no traffic, rising CPU.
                Log.e(TAG, "VpnService.protect() failed — traffic will loop back into the tunnel")
            }
            ok
        }

    private fun writeConfig(json: String): File {
        // Internal storage, not cache: §5.6 — the config holds the UUID and
        // REALITY key, and cache is more readily harvested.
        val file = File(filesDir, CONFIG_NAME)
        file.writeText(json)
        return file
    }

    /** §5.2, half two. The `dns` block in the generated config is half one. */
    private fun establishTun(): ParcelFileDescriptor? {
        val builder =
            Builder()
                .setSession(getString(R.string.tunnel_session_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addRoute("::", 0)
                .addDnsServer(DNS_SERVER)

        // §8: the app must never be routed through itself. Unlike a user-selected
        // package — where §8 says skip and continue — failing here would build
        // §5.1's loop by construction, so it is fatal.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "cannot exclude own package: ${e.javaClass.simpleName}")
            return null
        }

        return builder.establish()
    }

    private fun goForeground(textRes: Int) {
        val notification = TunnelNotification.build(this, getString(textRes))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // §14.1. The typed overload is API 29+, and systemExempted only
            // becomes meaningful on API 34, so below Q the untyped call is both
            // the only option and the correct one.
            startForeground(
                TunnelNotification.ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(TunnelNotification.ID, notification)
        }
    }

    // ── Teardown ────────────────────────────────────────────────────────────

    /**
     * Idempotent, and reachable from three unsynchronised places (§5.4).
     *
     * State is taken under [lock] in one shot; the slow work then runs outside it
     * so a wedged `quit()` cannot block publication. A second concurrent call
     * finds every field already null and does nothing twice.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun stopTunnel(finalState: ConnectionState) {
        val xray: XrayController?
        val fd: ParcelFileDescriptor?
        val cfg: File?

        synchronized(lock) {
            // Supersede any in-flight start before taking ownership of its state.
            ++generation
            xray = controller
            fd = tunInterface
            cfg = configFile
            controller = null
            tunInterface = null
            configFile = null
            publishLocked(ConnectionState.Disconnecting)
        }

        // Order matters: stop feeding packets in before removing their destination.
        try {
            Tun2Socks.stop()
        } catch (e: Throwable) {
            // Includes NoClassDefFoundError when System.loadLibrary failed. §5.4
            // says teardown must still finish — abandoning here leaks the fd.
            Log.e(TAG, "tun2socks stop failed: ${e.javaClass.simpleName}")
        }

        try {
            fd?.close()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "closing tun fd failed: ${e.javaClass.simpleName}")
        }

        // stopBlocking(), not stop(): onDestroy has no scope that outlives it and
        // §5.4 requires teardown to finish before the process dies. It also drops
        // the protector so Go stops holding this service.
        xray?.stopBlocking()
        cfg?.delete()

        stopForeground(STOP_FOREGROUND_REMOVE)
        publish(finalState)
    }

    /**
     * §5.4: called when another VPN app takes over or the user revokes
     * permission.
     *
     * `super.onRevoke()` is deliberately not called: its default implementation
     * is `stopSelf()`, which would run [onDestroy] and overwrite this Revoked
     * state with a plain Disconnected — losing the one piece of information the
     * user needs. We stop explicitly instead.
     */
    override fun onRevoke() {
        stopTunnel(failure(FailureReason.Revoked, "VPN permission revoked"))
        stopSelf()
    }

    override fun onDestroy() {
        // Preserve a terminal failure (notably Revoked) rather than flattening it.
        val finalState =
            synchronized(lock) {
                currentState as? ConnectionState.Failed ?: ConnectionState.Disconnected
            }
        stopTunnel(finalState)
        callbacks.kill()
        scope.cancel()
        super.onDestroy()
    }

    // ── Latency measurement ─────────────────────────────────────────────────

    /**
     * Measures one server, in whichever mode the run asked for.
     *
     * §5.1 is the whole reason this runs in `:bg`: this is the only place a live
     * `VpnService` exists to protect the socket. While a session is up, an
     * unprotected measurement is routed back into the TUN and times the server
     * *through* the tunnel rather than timing the server. While no session is up
     * `protect` is a harmless no-op — there is nothing to escape.
     *
     * `ping` builds its own core through libXray's `StartXray`, which never
     * touches the `coreServer` singleton `runXray`/`stopXray` guard, so this does
     * not disturb a running tunnel. That is read from upstream source and is the
     * central claim the device checklist exists to confirm.
     */
    private suspend fun measureOne(
        profile: StoredProfile,
        options: LatencyOptions,
    ): LatencyResult =
        // Checked before either mode runs: under another client's VPN neither can
        // reach the server, and both would return a number timing that client's
        // local endpoint instead (§10.1).
        if (foreignVpn.holdsDefaultRoute(ownTunnelActive())) {
            LatencyResult.failed(LatencyOutcome.FOREIGN_VPN)
        } else {
            when (options.mode) {
                PingMode.TCP -> measureTcp(profile, options)

                PingMode.PROXY_HEAD -> {
                    val outbound = profile.outbound
                    if (outbound == null) {
                        // A RAW_JSON row whose outbound could not be projected. Not
                        // a network failure, and saying "unreachable" would send the
                        // user looking for a problem with their server.
                        LatencyResult.failed(LatencyOutcome.UNSUPPORTED)
                    } else {
                        ProxyHeadProbe(LibXrayPingApi(), cacheDir).measure(
                            Profile(id = profile.id.toString(), name = profile.name, outbound = outbound),
                            options,
                        )
                    }
                }
            }
        }

    /**
     * `protect` keeps the socket out of **our** tunnel, which is what makes
     * measuring while connected report the server's real RTT — confirmed on a
     * device run, and only working at all because `TcpProbe` binds the socket
     * first so there is a file descriptor to mark.
     *
     * It does nothing about another app's VPN; [ForeignVpn] is checked before
     * this is reached, and refuses rather than returning the meaningless number
     * such a measurement would produce.
     */
    private suspend fun measureTcp(
        profile: StoredProfile,
        options: LatencyOptions,
    ): LatencyResult {
        val protector = TcpSocketProtector { socket -> protect(socket) }
        return TcpProbe(protector = protector)
            .measure(profile.address, profile.port, options.timeoutSeconds)
    }

    private fun pingModeOf(wire: Int): PingMode =
        if (wire == LatencyOptionsParcel.MODE_TCP) PingMode.TCP else PingMode.PROXY_HEAD

    /** True while this service holds a session, in any state but a settled down one. */
    private fun ownTunnelActive(): Boolean =
        synchronized(lock) {
            currentState !is ConnectionState.Disconnected && currentState !is ConnectionState.Failed
        }

    // ── IPC ─────────────────────────────────────────────────────────────────

    private val binder =
        object : ITunnelService.Stub() {
            override fun connect(profile: ProfileParcel) {
                // §10.4: fail loudly and specifically. A parcel carrying a
                // discriminant this process cannot name decodes to null rather
                // than to a plausible default — see ProfileParcel.toProfile.
                // Connecting on a guess would dial the wrong protocol, or drop
                // REALITY and go out in the clear, at a real address.
                val decoded = profile.toProfile()
                if (decoded == null) {
                    Log.e(TAG, "connect refused: profile parcel carries an unknown discriminant")
                    publish(
                        failure(
                            FailureReason.ProfileDecodeFailed,
                            "profile could not be decoded",
                        ),
                    )
                    stopSelf()
                    return
                }
                startTunnel(decoded, profile.rowId)
            }

            override fun disconnect() {
                stopTunnel(ConnectionState.Disconnected)
                stopSelf()
            }

            override fun getState(): ConnectionStateParcel =
                synchronized(lock) { ConnectionStateParcel.from(currentState) }

            override fun registerCallback(callback: ITunnelCallback) {
                callbacks.register(callback)
            }

            override fun unregisterCallback(callback: ITunnelCallback) {
                callbacks.unregister(callback)
            }

            override fun startLatencyRun(
                runId: Long,
                profileIds: LongArray?,
                modes: IntArray?,
                options: LatencyOptionsParcel?,
                callback: ILatencyCallback?,
            ) {
                // Any of the three being null means a caller on the other side of
                // the binder sent something malformed. Nothing to report and
                // nobody to report it to, so drop the run rather than guess.
                val ids = profileIds
                val resolved = options?.toOptions()
                val target = callback
                if (ids == null || resolved == null || target == null) {
                    Log.w(TAG, "latency run refused: incomplete request")
                    return
                }
                latencyRunner.start(
                    runId = runId,
                    profileIds = ids,
                    // Per index, because `ping-type` is scoped to the subscription
                    // that delivered it (§A.1) and one run can span groups whose
                    // providers chose differently. A missing or short array falls
                    // back to the run's own mode — the global setting.
                    optionsFor = { index ->
                        val mode = modes?.getOrNull(index)
                        if (mode == null) resolved else resolved.copy(mode = pingModeOf(mode))
                    },
                    onResult = { id, profileId, result ->
                        deliverLatency { target.onResult(id, profileId, result.delayMillis, result.outcome.ordinal) }
                    },
                    onFinished = { id -> deliverLatency { target.onFinished(id) } },
                )
            }

            override fun cancelLatencyRun(runId: Long) {
                latencyRunner.cancel(runId)
            }
        }

    /**
     * A dead `:main` is ordinary here, not an error — the user navigated away or
     * the UI process was reclaimed while a measurement was still running. The
     * exception is swallowed without its message for §5.6: a `DeadObjectException`
     * from this path carries nothing useful, and logging binder failures around
     * latency would produce a line per row on every backgrounded run.
     */
    @Suppress("SwallowedException")
    private inline fun deliverLatency(send: () -> Unit) {
        try {
            send()
        } catch (e: android.os.RemoteException) {
            // :main went away. The run continues; nobody is listening.
        }
    }

    /**
     * The system binds with [SERVICE_INTERFACE] for always-on VPN and must get
     * `VpnService`'s own binder; the app gets ours. Getting this backwards breaks
     * always-on in a way that only shows up after a reboot.
     */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    private companion object {
        const val CONFIG_NAME = "xray-config.json"
    }
}

/**
 * Writes the one persistence side effect `:bg` performs (spec D4): the outcome
 * of a connect attempt, against the profile row [ProfileParcel.rowId] names.
 *
 * Takes [recordConnected] and [recordError] as suspend function references —
 * `ProfileRepository::recordConnected`/`recordError` in production — rather
 * than an [art.yniyniyni.subspace.core.data.ProfileRepository] directly.
 * `ProfileRepository`'s constructor is `internal` to `:core:data`, so `:service`
 * cannot build a real instance to test against, and this project carries no
 * mocking library that would let a test fake one either (§10.7 does not
 * justify adding one for two methods). This indirection is what keeps
 * `ConnectionRecordingTest` a plain JVM unit test.
 *
 * §5.3: only ever called from `:bg`'s IO-dispatched start sequence.
 * §10.4: a persistence failure must not take the tunnel down, but it must not
 * vanish silently either — [onFailure] receives it, and [TunnelService] logs
 * only the exception's class name (§5.6: a Room/SQLite failure message can
 * echo back the value that failed to write).
 */
internal class ConnectionRecorder(
    private val recordConnected: suspend (rowId: Long, atEpochMillis: Long) -> Unit,
    private val recordError: suspend (rowId: Long, redactedDetail: String) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    /**
     * Records [state] against [rowId] when it is a terminal outcome
     * ([ConnectionState.Connected] or [ConnectionState.Failed]); a no-op for
     * every other [ConnectionState], which is not something this class persists.
     */
    @Suppress("TooGenericExceptionCaught") // §10.4: a write failure here must never propagate into the start sequence.
    suspend fun record(
        rowId: Long,
        state: ConnectionState,
    ) {
        try {
            when (state) {
                is ConnectionState.Connected -> recordConnected(rowId, System.currentTimeMillis())
                is ConnectionState.Failed -> recordError(rowId, state.detail)
                else -> Unit
            }
        } catch (e: CancellationException) {
            // Rethrown ahead of the catch below, which would otherwise absorb it:
            // CancellationException is an Exception, so `catch (e: Exception)`
            // swallowed service-scope cancellation and reported it as a failed
            // write (PR #4 review, P1 finding A). Swallowing it also left the
            // caller running, which is how a coroutine cancelled mid-record could
            // carry on into whatever followed the write.
            throw e
        } catch (e: Exception) {
            onFailure(e)
        }
    }
}
