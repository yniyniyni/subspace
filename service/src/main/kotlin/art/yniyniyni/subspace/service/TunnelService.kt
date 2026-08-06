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
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.failure
import art.yniyniyni.subspace.core.xray.ConfigResult
import art.yniyniyni.subspace.core.xray.SocketProtector
import art.yniyniyni.subspace.core.xray.TunnelSettings
import art.yniyniyni.subspace.core.xray.XrayConfigGenerator
import art.yniyniyni.subspace.core.xray.XrayController
import art.yniyniyni.subspace.core.xray.XrayException
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    // Built in onCreate, once profileRepository is injected. Wraps the
    // repository's two spec-D4 methods as plain suspend lambdas rather than
    // handing ConnectionRecorder the repository itself — see ConnectionRecorder's
    // KDoc for why that indirection is what keeps it unit-testable.
    private lateinit var connectionRecorder: ConnectionRecorder

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

    private val lock = Any()

    // All guarded by `lock`.
    private var controller: XrayController? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var configFile: File? = null
    private var generation = 0
    private var currentState: ConnectionState = ConnectionState.Disconnected

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
    // stage of the start sequence to fail specifically and stop there.
    @Suppress("ReturnCount")
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
        val settings = TunnelSettings(socksPort, DNS_SERVER, enableSniffing = true)
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
        if (!publishIfCurrent(gen, connected)) return
        // The one write :bg performs on success (spec D4) — see ConnectionRecorder.
        connectionRecorder.record(rowId, connected)
        goForeground(R.string.notification_connected)
    }

    /**
     * Publishes a specific failure and cleans up what a failed start leaves.
     *
     * §5.6: the config file holds the UUID and REALITY key. A failed start used
     * to leave it on disk indefinitely, because only teardown deleted it.
     *
     * `suspend`, not plain: the one write `:bg` performs on failure (spec D4) runs
     * here, after the lock is released — `synchronized` cannot contain a suspend
     * call, which is also why the record happens outside the block rather than
     * inside it.
     */
    private suspend fun failStart(
        gen: Int,
        reason: FailureReason,
        cause: Exception,
        rowId: Long,
    ): Nothing? {
        val failed =
            synchronized(lock) {
                if (gen != generation) return null
                configFile?.delete()
                configFile = null
                controller = null
                // failure() redacts at construction — libXray's errors quote the
                // config straight back (§5.6).
                val state = failure(reason, cause.message.orEmpty())
                publishLocked(state)
                state
            }
        connectionRecorder.record(rowId, failed)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        } catch (e: Exception) {
            onFailure(e)
        }
    }
}
