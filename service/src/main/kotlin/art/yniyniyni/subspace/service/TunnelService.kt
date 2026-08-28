// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.PerAppRepository
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.RuleSetAssets
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
import art.yniyniyni.subspace.core.xray.ComposeResult
import art.yniyniyni.subspace.core.xray.ConfigResult
import art.yniyniyni.subspace.core.xray.DnsPlan
import art.yniyniyni.subspace.core.xray.DnsPlanner
import art.yniyniyni.subspace.core.xray.LibXrayPingApi
import art.yniyniyni.subspace.core.xray.ProxyHeadProbe
import art.yniyniyni.subspace.core.xray.RawConfigComposer
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
import org.json.JSONException
import org.json.JSONObject
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

// No longer "the" resolver: since M6.5, a session's DnsPlan chooses what the app
// actually queries and what it advertises on the TUN (DnsPlan.tunAdvertisedAddress).
// This is only the fallback for a plan that yields no address literal — a DoH-only
// setting or profile with no IP the TUN can be handed — and for the null-plan case
// where nothing asked for DNS at all (spec §7.4's M1-compatible path).
private const val DNS_SERVER = "1.1.1.1"

// Fix round 1, Finding 4: sniffing is not a user setting in this build, so both
// DnsPlanner.plan's sniffingEnabled and TunnelSettings.enableSniffing must read
// this one constant rather than repeat the literal `true` in two places coupled
// only by a comment — if sniffing ever becomes configurable, both call sites
// change together because there is only one place to change.
private const val SNIFFING_ENABLED = true

/** Decodes only this service's explicit connect request without exposing/logging its payload. */
@Suppress("DEPRECATION")
internal fun connectProfileFrom(intent: Intent?): ProfileParcel? {
    if (intent?.action != ACTION_CONNECT) return null
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_PROFILE, ProfileParcel::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_PROFILE)
    }
}

/** [passthroughPlanFor]'s answer: what a passthrough compose call should do about routing/DNS. */
internal data class PassthroughPlan(
    /** Spec §4.3: what `xray.location.asset` must name — see [passthroughPlanFor]. */
    val assetDir: String,
    /** Whether the app's own `routing`/`dns` should replace the stored config's own. */
    val overrideApplies: Boolean,
)

/**
 * Spec §4.3, and the one override-branch decision this file must not make
 * twice with two different conditions.
 *
 * The asset directory a passthrough config's `env` block names follows the
 * routing state alone — [routingActive] — never whether an override applies.
 * An override, in turn, applies whenever routing is active *or* a DNS plan
 * exists ([dnsPlanPresent]), which is a strictly wider condition than
 * [routingActive] alone: a routing-off session with a non-default DNS plan
 * takes the override branch but must still resolve to [flatRoot], not
 * [activeAssetDir]. Deriving both answers from the same two booleans in one
 * function — rather than, say, keying the asset dir off "is there an
 * override" — is what makes the wrong wiring structurally unavailable rather
 * than merely undesirable; a `PassthroughStartTest` case pins exactly the
 * routing-off-plus-DNS-plan combination this guards.
 *
 * [activeAssetDir] is nullable so "routing is active but its own asset
 * directory is unknown" cannot be silently misread as "use the empty
 * string" — it is read only when [routingActive] is true, and even then
 * falls back to [flatRoot] rather than writing an empty
 * `xray.location.asset`.
 *
 * Extracted as a standalone function, rather than inlined where it is used, so
 * this one rule is testable on the JVM without constructing a
 * [RoutingResolution] or a `GeoAssetRepository`.
 */
internal fun passthroughPlanFor(
    routingActive: Boolean,
    dnsPlanPresent: Boolean,
    activeAssetDir: String?,
    flatRoot: String,
): PassthroughPlan =
    PassthroughPlan(
        assetDir = activeAssetDir.takeIf { routingActive } ?: flatRoot,
        overrideApplies = routingActive || dnsPlanPresent,
    )

/**
 * Which [FailureReason] a core refusal at `xray.validate` names.
 *
 * The backstop two accepted M7 limitations depend on: core validation at
 * import does not run on the periodic subscription-refresh path, and is
 * skipped when geo assets are not yet installed. Both are acceptable only
 * because a stored config the core later refuses fails visibly, with this
 * accurate reason, at connect time — a `ConfigRejected` here would read as
 * "our own generation is broken" instead of "your stored config no longer
 * runs", sending the user looking for the wrong kind of fix.
 */
internal fun validationFailureReason(runsAsWritten: Boolean): FailureReason =
    if (runsAsWritten) FailureReason.PassthroughRejectedAtConnect else FailureReason.ConfigRejected

/**
 * The tags already on a stored config's own `outbounds`, so
 * [TunnelService.composePassthrough] can keep the override's stock
 * `direct`/`block`/`dns-out` outbounds from colliding with one the config
 * already names — `RawConfigComposer` only ever appends outbounds, so an
 * unfiltered duplicate tag is a config the core is not obliged to accept.
 *
 * Malformed input (should not reach here: `runsAsWritten` implies this row
 * passed structural analysis at import) yields an empty set rather than
 * throwing — the worst case is then an unfiltered append, which is exactly
 * today's behaviour absent this filter, not a crash.
 */
@Suppress("SwallowedException") // Malformed JSON here degrades to "no known tags", not a crash — see above.
private fun existingOutboundTags(rawJson: String): Set<String> =
    try {
        val outbounds = JSONObject(rawJson).optJSONArray("outbounds") ?: return emptySet()
        buildSet {
            for (i in 0 until outbounds.length()) {
                outbounds.optJSONObject(i)?.optString("tag")?.takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    } catch (e: JSONException) {
        emptySet()
    }

/** This outbound literal's own `tag`, or null if it has none this can read. */
@Suppress("SwallowedException") // A literal this can't parse is treated as untagged, not a crash.
private fun String.tagOf(): String? =
    try {
        JSONObject(this).optString("tag").takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }

/** Returns the same-UID debug-test callback only; release builds ignore this extra. */
@Suppress("DEPRECATION")
internal fun testConnectObserverFrom(
    intent: Intent?,
    debuggable: Boolean,
): Messenger? {
    if (!debuggable || intent?.action != ACTION_CONNECT) return null
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_TEST_CONNECT_OBSERVER, Messenger::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_TEST_CONNECT_OBSERVER)
    }
}

/**
 * Owns the tunnel.
 *
 * Runs in `:bg` (§3). Nothing here may be reached from `:main` except through
 * [ITunnelService] — the processes share no memory, so a Hilt singleton or an
 * `object` is two different instances.
 *
 * ## Concurrency
 *
 * §5.4 teardown still has platform entry points outside the UI-command stream:
 * `onRevoke()` arrives through `VpnService`, `onDestroy()` on the main thread,
 * and the start sequence runs on IO. Connect, disconnect, and per-app reapply
 * first enter [commandCoordinator], while [lock] and [generation] handle those
 * platform callbacks racing the asynchronous start sequence. So:
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
@Suppress("LargeClass", "TooManyFunctions")
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
    lateinit var perAppRepository: PerAppRepository

    @Inject
    lateinit var geoAssetRepository: GeoAssetRepository

    @Inject
    lateinit var ruleSetAssets: RuleSetAssets

    // Built in onCreate, once profileRepository is injected. Wraps the
    // repository's two spec-D4 methods as plain suspend lambdas rather than
    // handing ConnectionRecorder the repository itself — see ConnectionRecorder's
    // KDoc for why that indirection is what keeps it unit-testable.
    private lateinit var connectionRecorder: ConnectionRecorder

    // Built in onCreate, once its data collaborators above are injected. Same
    // suspend-lambda indirection as connectionRecorder, for the same reason —
    // see RoutingResolver's KDoc.
    private lateinit var routingResolver: RoutingResolver

    // Built in onCreate for the same reason routingResolver is, and reading the
    // selection at resolve time rather than at construction — see PerAppResolver.
    private lateinit var perAppResolver: PerAppResolver

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
     * The profile the live session was started from, so a per-app change can
     * rebuild the tunnel without :main re-supplying one (§5.5: what is connected
     * is this process's fact, not the UI's).
     *
     * Cleared by both terminal paths a start can take: [stopTunnel] (disconnect,
     * revoke, `onDestroy`) so a settled-down service cannot be restarted into a
     * session the user ended, and [failStart] so a session that never came up
     * does not linger here either. The coordinator's `reapplyPerApp` additionally
     * gates on [ownTunnelActive] rather than trusting this field's nullness
     * alone, but a stale non-null value here would still be a latent trap for
     * the next reader, not just a harmless one.
     */
    private data class LiveSession(
        val profile: Profile,
        val rowId: Long,
        val startId: Int,
    )

    private var liveSession: LiveSession? = null
    private var activeStartId = 0

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

    /** UI tunnel commands mutate session state only from this single consumer. */
    private val commandCoordinator =
        TunnelCommandCoordinator(
            scope = scope,
            connect = ::connectFromCommand,
            rejectConnect = ::rejectConnectFromCommand,
            disconnect = { startId ->
                stopTunnel(ConnectionState.Disconnected)
                stopStartedService(startId)
            },
            reapplyPerApp = ::reapplyPerAppFromCommand,
            observeConnect = ::observeConnectFromCommand,
        )
    private val commandIngress = TunnelCommandIngress(commandCoordinator::enqueue)

    override fun onCreate() {
        super.onCreate()
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
                loadStored = routingRepository::stored,
                installedGeoFiles = { directory ->
                    directory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
                },
                assetScope = ruleSetAssets,
            )
        perAppResolver =
            PerAppResolver(
                selection = { perAppRepository.selection.first() },
                ownPackage = { packageName },
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
    ): Int {
        val request = connectProfileFrom(intent)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val testObserver = testConnectObserverFrom(intent, debuggable)
        if (!commandIngress.started(request, startId, testObserver)) {
            Log.w(TAG, "connect request dropped: service command queue closed")
            stopStartedService(startId)
        }
        return START_NOT_STICKY
    }

    /** Reports actor receipt to a same-UID debug test without changing the connect path. */
    @Suppress("SwallowedException")
    private fun observeConnectFromCommand(command: TunnelCommand.Connect) {
        val observer = command.testObserver ?: return
        val message =
            Message.obtain(null, TEST_CONNECT_RECEIVED).apply {
                data = android.os.Bundle().apply { putParcelable(EXTRA_PROFILE, command.profile) }
            }
        try {
            observer.send(message)
        } catch (e: android.os.RemoteException) {
            // The debug instrumentation process ended before actor receipt.
        }
    }

    private suspend fun rejectConnectFromCommand(
        startId: Int,
        rowId: Long,
    ) {
        Log.e(TAG, "connect request refused: ProfileDecodeFailed")
        val failed = failure(FailureReason.ProfileDecodeFailed, "connect request could not be decoded")
        stopTunnel(failed)
        stopStartedService(startId)
        connectionRecorder.record(rowId, failed)
    }

    private fun rejectInitialForegroundLifecycle(
        gen: Int,
        rowId: Long,
    ) {
        val failed = failure(FailureReason.CoreStartFailed, FOREGROUND_LIFECYCLE_REJECTED)
        val startId = stopTunnel(failed, expectedGeneration = gen) ?: return
        stopStartedService(startId)
        scope.launch { connectionRecorder.record(rowId, failed) }
    }

    private suspend fun handleForegroundLifecycleRejection(
        gen: Int,
        rowId: Long,
    ) {
        val failed = failure(FailureReason.CoreStartFailed, FOREGROUND_LIFECYCLE_REJECTED)
        val startId = stopTunnel(failed, expectedGeneration = gen) ?: return
        stopStartedService(startId)
        connectionRecorder.record(rowId, failed)
    }

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
        startId: Int,
    ) {
        val gen =
            synchronized(lock) {
                // §5.5 makes this service the source of truth, so it cannot rely
                // on the UI to prevent a second connect. Without this guard the
                // previous TUN fd leaks and the old core runs on unreferenced.
                if (currentState !is ConnectionState.Disconnected &&
                    currentState !is ConnectionState.Failed
                ) {
                    activeStartId = startId
                    liveSession = liveSession?.copy(startId = startId)
                    Log.w(TAG, "connect ignored: a session is already active")
                    return
                }
                activeStartId = startId
                liveSession = LiveSession(profile, rowId, startId)
                val nextGeneration = ++generation
                // Claim the session before the coordinator accepts another
                // command. Otherwise a second queued connect can observe the
                // old Disconnected state before the startup coroutine runs.
                publishLocked(ConnectionState.Connecting(StartupStage.AllocatingPort))
                nextGeneration
            }

        runAfterForegroundEstablished(
            establishForeground = { goForeground(R.string.notification_connecting) },
            onRejected = { rejectInitialForegroundLifecycle(gen, rowId) },
            launchStartup = {
                scope.launch {
                    val started = resolveAndStartCore(gen, profile, rowId) ?: return@launch
                    attachTun(gen, started.xray, started.ports, started.dnsPlan, rowId)
                }
            },
        )
    }

    /**
     * The two ports [startCore] allocates together, carried to [attachTun].
     *
     * Not a bare `Pair<Int, Int>`: `ports.first`/`ports.second` at the call site
     * would be one more place a reviewer has to remember which index is which,
     * for a mistake that decodes to a plausible port either way.
     */
    private data class StartedPorts(val socksPort: Int, val httpPort: Int)

    /**
     * A started core leaves the routing-generation lease but still needs its TUN
     * attached.
     *
     * [dnsPlan] rides along rather than being recomputed in [attachTun]: it is
     * already baked into the generated config by [startCore], and a second
     * `DnsPlanner.plan` call there — even from the same inputs — is one more place
     * the TUN's advertised resolver and the config's `dns.servers` could disagree
     * if either input changed between the two reads.
     */
    private data class StartedCore(val xray: XrayController, val ports: StartedPorts, val dnsPlan: DnsPlan?)

    /**
     * Resolves routing, builds the controller, validates, and starts Xray inside
     * one retained-generation scope. Every return and throw exits that scope,
     * so success, named failure, cancellation, and supersession all release the
     * lease only after `validate`/`start` have finished consuming the assets.
     */
    @Suppress("TooGenericExceptionCaught") // Resolver lambdas cross Room and filesystem boundaries.
    private suspend fun resolveAndStartCore(
        gen: Int,
        profile: Profile,
        rowId: Long,
    ): StartedCore? =
        try {
            routingResolver.withResolution { routing ->
                if (routing is RoutingResolution.MissingGeoData) {
                    failStart(
                        gen,
                        FailureReason.GeoDataMissing,
                        IllegalStateException(routing.missing.sorted().joinToString(", ")),
                        rowId,
                    )
                    return@withResolution null
                }
                // Built here, from the same already-resolved rule set the geo `env`
                // uses, rather than in startCore or attachTun — both need the exact
                // same plan, and computing it once is what keeps the generated
                // config and the TUN's advertised resolver from disagreeing.
                val dnsPlan =
                    DnsPlanner.plan(
                        profileDns = (routing as? RoutingResolution.Active)?.dns,
                        setting = settingsRepository.dnsResolver.first(),
                        routing = (routing as? RoutingResolution.Active)?.ruleSet,
                        sniffingEnabled = SNIFFING_ENABLED,
                    )
                val assetDir =
                    (routing as? RoutingResolution.Active)?.assetDir
                        ?: geoAssetRepository.geoDirectory()
                val xray = XrayController(geoAssetDir = assetDir)
                val ownsStart =
                    synchronized(lock) {
                        if (gen != generation) {
                            false
                        } else {
                            controller = xray
                            true
                        }
                    }
                if (!ownsStart) return@withResolution null

                val ports = startCore(gen, xray, profile, rowId, routing, dnsPlan) ?: return@withResolution null
                StartedCore(xray, ports, dnsPlan)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failStart(gen, FailureReason.CoreStartFailed, e, rowId)
            null
        }

    // One early return per step is the point, not a smell: §10.4 requires each
    // stage of the start sequence to fail specifically and stop there.
    //
    // LongParameterList: gen/rowId identify the attempt, xray/profile/routing are
    // the three inputs the config generator needs, and dnsPlan is the plan
    // resolveAndStartCore already computed once from routing — recomputing it
    // here to shrink the list would risk the TUN and the config disagreeing
    // (§7.4). Six genuinely distinct inputs, not one bundle hiding as several.
    @Suppress("ReturnCount", "LongParameterList")
    private suspend fun startCore(
        gen: Int,
        xray: XrayController,
        profile: Profile,
        rowId: Long,
        routing: RoutingResolution,
        dnsPlan: DnsPlan?,
    ): StartedPorts? {
        // One call for both ports, not two calls to allocatePort(): §10.6 and
        // docs/agent/research/libxray-api.md §5 — getFreePorts closes each
        // listener before opening the next, so nothing stops the kernel handing
        // back the same number twice even across separate calls. allocatePorts
        // verifies distinctness and retries; a config with two inbounds on the
        // same port is rejected by the core outright.
        val ports =
            try {
                xray.allocatePorts(count = 2)
            } catch (e: XrayException) {
                return failStart(gen, FailureReason.PortAllocationFailed, e, rowId)
            }
        val socksPort = ports[0]
        val httpPort = ports[1]

        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.GeneratingConfig))) return null
        val settings =
            TunnelSettings(
                socksPort = socksPort,
                dnsServer = DNS_SERVER,
                enableSniffing = SNIFFING_ENABLED,
                routing = (routing as? RoutingResolution.Active)?.ruleSet,
                httpPort = httpPort,
                dns = dnsPlan,
            )
        // §10.4/failStart's cleanup applies here too, not just to the try/catch
        // below: an early return that only published a state (skipping
        // stopForeground/stopSelf/controller = null) would leave a stuck
        // "Connecting" notification, same as every other branch in this function.
        val (json, runsAsWritten) =
            when (val outcome = resolveConfigJson(profile, settings, routing, dnsPlan, rowId)) {
                is ConfigJsonOutcome.Ok -> outcome.json to outcome.runsAsWritten
                is ConfigJsonOutcome.Failed -> return failStart(gen, outcome.reason, outcome.cause, rowId)
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
            // A stored config that passed import can still be refused here —
            // the core, the environment, or the row's own bytes changed since.
            // §10.4: that is a different, user-actionable fact from "our own
            // typed generation produced something the core dislikes"
            // (ConfigRejected), and §6 forbids silently falling back to the
            // typed projection instead of naming it.
            return failStart(gen, validationFailureReason(runsAsWritten), e, rowId)
        }

        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.StartingCore))) return null
        try {
            xray.start(file, protector())
        } catch (e: XrayException) {
            return failStart(gen, FailureReason.CoreStartFailed, e, rowId)
        }

        return StartedPorts(socksPort, httpPort)
    }

    /** [resolveConfigJson]'s outcome — a JSON string ready for [writeConfig], or a named failure. */
    private sealed interface ConfigJsonOutcome {
        /** @property runsAsWritten Whether [json] is the row's own composed bytes, not a typed generation. */
        data class Ok(val json: String, val runsAsWritten: Boolean) : ConfigJsonOutcome

        data class Failed(val reason: FailureReason, val cause: Exception) : ConfigJsonOutcome
    }

    /**
     * Produces the config text [startCore] hands to [writeConfig] — the row's
     * own bytes for a `runsAsWritten` profile, or a typed generation otherwise.
     *
     * The raw bytes come from Room, not the Parcel: `ProfileParcel` carries
     * typed columns only (§5.6 — a Binder transaction is capped near 1MB and a
     * pasted config has no bound), so whether this row runs as written is read
     * fresh here, the same way the latency path already reads a row by id
     * (`loadProfile = { id -> profileRepository.profile(id) }`).
     */
    @Suppress("ReturnCount") // One early return per distinct outcome, same reasoning as startCore's own.
    private suspend fun resolveConfigJson(
        profile: Profile,
        settings: TunnelSettings,
        routing: RoutingResolution,
        dnsPlan: DnsPlan?,
        rowId: Long,
    ): ConfigJsonOutcome {
        // A row that vanished between connect and here — a subscription sync
        // racing a mid-flight connect can delete or re-key it — is not "assume
        // typed": §6 forbids silently running the typed projection behind a
        // profile the user may have imported as passthrough, and once the row
        // is gone there is no way to ask Room which this was.
        val stored =
            profileRepository.profile(rowId) ?: return ConfigJsonOutcome.Failed(
                FailureReason.ConfigGenerationFailed,
                IllegalStateException("profile row is gone"),
            )
        if (stored.runsAsWritten) {
            // Invariant, not expected to be reachable: `runsAsWritten` only
            // reads true for a RAW_JSON row (StoredProfile.runsAsWritten),
            // which always carries its bytes. Fail loudly rather than fall
            // back to the typed `profile` argument — §6 forbids running a
            // config this row was never validated as, silently, behind the
            // one state the user can see.
            val rawJson =
                stored.rawJson ?: return ConfigJsonOutcome.Failed(
                    FailureReason.ConfigGenerationFailed,
                    IllegalStateException("a runsAsWritten row stored no bytes"),
                )
            return when (val composed = composePassthrough(rawJson, settings, routing, dnsPlan)) {
                is ComposeResult.Ok -> ConfigJsonOutcome.Ok(composed.json, runsAsWritten = true)
                // Distinct from PassthroughRejectedAtConnect, which startCore uses when the
                // *core* refuses a well-formed config (§10.4): this means composition itself
                // failed — a condition import already screens for structurally, so reaching
                // it here is an environment or data change, not a core verdict.
                is ComposeResult.Failed ->
                    ConfigJsonOutcome.Failed(
                        FailureReason.ConfigGenerationFailed,
                        IllegalStateException("stored config did not compose: ${composed.reason}"),
                    )
            }
        }
        return when (val config = XrayConfigGenerator.generate(profile, settings)) {
            is ConfigResult.Unsupported ->
                ConfigJsonOutcome.Failed(
                    FailureReason.ProtocolNotSupported,
                    IllegalArgumentException("${config.protocol} is not supported yet"),
                )

            is ConfigResult.Ok -> ConfigJsonOutcome.Ok(config.json, runsAsWritten = false)
        }
    }

    /**
     * Composes a `runsAsWritten` row's own bytes, in place of
     * [XrayConfigGenerator.generate].
     *
     * [passthroughPlanFor] decides, from the same two booleans, both whether
     * the app's own `routing`/`dns` replaces the config's own and which geo
     * asset directory the composed config's `env` block names (spec §4.3) —
     * see its KDoc for why those two answers come from one function rather
     * than two independently-wired conditions. The override's stock
     * `direct`/`block`/`dns-out` outbounds are filtered against the stored
     * config's own tags first: `RawConfigComposer` only ever appends, so an
     * unfiltered duplicate tag (a `direct` freedom outbound is common) would be
     * a config the core is not obliged to accept.
     */
    private fun composePassthrough(
        rawJson: String,
        settings: TunnelSettings,
        routing: RoutingResolution,
        dnsPlan: DnsPlan?,
    ): ComposeResult {
        val plan =
            passthroughPlanFor(
                routingActive = routing is RoutingResolution.Active,
                dnsPlanPresent = dnsPlan != null,
                activeAssetDir = (routing as? RoutingResolution.Active)?.assetDir?.absolutePath,
                flatRoot = geoAssetRepository.geoDirectory().absolutePath,
            )
        val override =
            if (plan.overrideApplies) {
                val existingTags = existingOutboundTags(rawJson)
                XrayConfigGenerator.overrideBlocks(settings)
                    .let { blocks ->
                        blocks.copy(
                            extraOutboundsJson = blocks.extraOutboundsJson.filter { it.tagOf() !in existingTags },
                        )
                    }
            } else {
                null
            }
        return RawConfigComposer.compose(rawJson, settings, plan.assetDir, override)
    }

    /** The per-app gate keeps "off" distinct from "resolution failed" for [attachTun]. */
    private sealed interface PerAppGateResult {
        data class Proceed(val plan: BuilderPlan) : PerAppGateResult

        /** [failStart] has already run — [attachTun] must return without doing anything else. */
        data object Failed : PerAppGateResult
    }

    /**
     * §8's gate is extracted from [attachTun] to keep the caller under
     * detekt's length threshold and one failure shape in one place.
     *
     * [PerAppResolver.resolve] reaches Room through the lambda built in
     * [onCreate], and nothing narrows what that read can throw. The consequence
     * is worse here than during routing resolution because by now
     * [startCore] has left a core running. Unguarded, the failure reaches
     * [errorHandler], which publishes [FailureReason.CoreStartFailed] but skips
     * both `xray.stop()` and failStart's §5.4 cleanup: a live Go runtime, a
     * non-null `controller`, the config file still on disk (§5.6) and a stuck
     * foreground notification, while `Failed` invites a second connect that would
     * overwrite `controller` and orphan the first core.
     */
    // Room's failure shape, not ours to narrow.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun resolvePerApp(
        gen: Int,
        xray: XrayController,
        rowId: Long,
    ): PerAppGateResult {
        val resolution =
            try {
                perAppResolver.resolve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                xray.stop()
                failStart(gen, FailureReason.CoreStartFailed, e, rowId)
                return PerAppGateResult.Failed
            }
        val plan = builderPlan(resolution)
        return if (plan == null) {
            failAfterCore(
                gen,
                xray,
                FailureReason.PerAppAllowListEmpty,
                "allow-list mode with no application selected",
                rowId,
            )
            PerAppGateResult.Failed
        } else {
            PerAppGateResult.Proceed(plan)
        }
    }

    /**
     * §5.4, the shape every failure past [startCore] shares: the core it left
     * running must not outlive a start we are abandoning.
     */
    private suspend fun failAfterCore(
        gen: Int,
        xray: XrayController,
        reason: FailureReason,
        detail: String,
        rowId: Long,
    ) {
        xray.stop()
        failStart(gen, reason, IllegalStateException(detail), rowId)
    }

    /**
     * [establishTun] plus its two §10.4 failures, kept out of [attachTun] so that
     * function stays under detekt's length threshold.
     *
     * @return null when [failStart] has already run. There is no third outcome
     *   to conflate, so a nullable fd says exactly what a sealed type would.
     */
    private suspend fun establishOrFail(
        gen: Int,
        xray: XrayController,
        plan: BuilderPlan,
        dnsPlan: DnsPlan?,
        rowId: Long,
    ): ParcelFileDescriptor? =
        when (val result = establishTun(plan, dnsPlan)) {
            is TunResult.Established -> result.fd
            // §10.4: the same reason an empty selection gets, because after the
            // skipping there is genuinely no application left to allow.
            TunResult.AllowListEmptied -> {
                failAfterCore(
                    gen,
                    xray,
                    FailureReason.PerAppAllowListEmpty,
                    "every allow-listed application is no longer installed",
                    rowId,
                )
                null
            }
            TunResult.Failed -> {
                failAfterCore(gen, xray, FailureReason.TunEstablishFailed, "establish() returned null", rowId)
                null
            }
        }

    /**
     * Builds the TUN interface and hands its fd to tun2socks.
     *
     * Every failure stops the core [startCore] left running — §5.4: a
     * half-started tunnel must not survive as a leaked fd plus a live runtime.
     */
    // Same reasoning as startCore: each early return is a distinct §10.4 failure
    // or a supersede check, and collapsing them would hide which one fired.
    //
    // LongParameterList: [ports] bundles socksPort/httpPort rather than taking
    // them separately, reusing the pairing [startCore] already established —
    // one fewer place to mismatch which index is which, and it keeps this
    // signature at five rather than six now that [dnsPlan] joined it.
    @Suppress("ReturnCount")
    private suspend fun attachTun(
        gen: Int,
        xray: XrayController,
        ports: StartedPorts,
        dnsPlan: DnsPlan?,
        rowId: Long,
    ) {
        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.EstablishingTun))) return
        val plan =
            when (val gate = resolvePerApp(gen, xray, rowId)) {
                is PerAppGateResult.Proceed -> gate.plan
                PerAppGateResult.Failed -> return
            }
        val fd = establishOrFail(gen, xray, plan, dnsPlan, rowId) ?: return
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
        val config = tun2socksConfig(socksPort = ports.socksPort, mtu = TUN_MTU)
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

        val connected = ConnectionState.Connected(System.currentTimeMillis(), ports.socksPort, ports.httpPort)
        // One generation-checked transition: the connected notification is established and
        // `Connected` published together under the lock, and the spec-D4 success write (see
        // ConnectionRecorder) happens strictly after. Previously this published, suspended in
        // the write, and called goForeground() on the way out — so a teardown during the
        // write left this coroutine to restore the connected notification over a tunnel that
        // was already down (§5.5). Nothing may be added after the `persist` lambda.
        val settlement =
            terminalOutcome.settleHandlingLifecycleRejection(
                gen = gen,
                state = connected,
                lifecycle = { goForeground(R.string.notification_connected) },
                persist = { connectionRecorder.record(rowId, connected) },
                onLifecycleRejected = { handleForegroundLifecycleRejection(gen, rowId) },
            )
        if (settlement != TerminalSettlement.Committed) return
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
                liveSession = null
                val startId = activeStartId
                activeStartId = 0
                removeForegroundSafely()
                stopStartedService(startId)
                true
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

    /**
     * How [establishTun] ended.
     *
     * A sealed type rather than a nullable fd because an emptied allow list and
     * a refused `establish()` are different
     * §10.4 failures, and a single null would have sent a user whose selected
     * apps were uninstalled looking for a broken tunnel instead of a broken
     * selection.
     */
    private sealed interface TunResult {
        data class Established(val fd: ParcelFileDescriptor) : TunResult

        /** The builder refused, or our own package could not be excluded. */
        data object Failed : TunResult

        /** Allow-list mode, and every package in it is gone — see [PackageApplication.nothingApplied]. */
        data object AllowListEmptied : TunResult
    }

    /**
     * §5.2, half two. The `dns` block in the generated config is half one.
     *
     * @param plan §8's per-app decision, already made — see [builderPlan] for why
     *   the decision is a type rather than a pair of booleans checked here.
     * @param dnsPlan the same plan already baked into the generated config's
     *   `dns` block, so the TUN advertises the resolver the session actually
     *   uses. [DNS_SERVER] is the fallback for a null plan, or one with no
     *   address literal to advertise — see [DnsPlan.tunAdvertisedAddress].
     */
    // Each return is a distinct outcome: the fatal own-package failure reached
    // from two arms of the plan, the emptied allow list, and success. Folding
    // them would mean building the whole interface before discovering we cannot
    // exclude ourselves, and would lose which failure the user is looking at.
    @Suppress("ReturnCount")
    private fun establishTun(
        plan: BuilderPlan,
        dnsPlan: DnsPlan?,
    ): TunResult {
        val builder =
            Builder()
                .setSession(getString(R.string.tunnel_session_name))
                .setMtu(TUN_MTU)
                .addAddress(TUN_ADDRESS, TUN_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                .addRoute("::", 0)
        builder.addDnsServerOrFallback(dnsPlan)

        when (plan) {
            BuilderPlan.DisallowOwnOnly -> if (!excludeSelf(builder)) return TunResult.Failed
            is BuilderPlan.Disallow -> {
                if (!excludeSelf(builder)) return TunResult.Failed
                // §8: skip and continue. One package uninstalled since it was
                // selected must never abort the whole tunnel setup, and excluding
                // nothing extra degrades towards DisallowOwnOnly — the safe way.
                logSkipped(applyEach(plan.packages) { builder.disallowOrSkip(it) })
            }
            // No excludeSelf() here, and that is not an omission. Calling
            // addDisallowedApplication after addAllowedApplication throws
            // UnsupportedOperationException (spec §2.2). We are excluded by being
            // absent from the allow list, which PerAppResolver guarantees.
            is BuilderPlan.Allow -> {
                val applied = applyEach(plan.packages) { builder.allowOrSkip(it) }
                // If nothing was added, AOSP never created the allowed list, and
                // a null list is "no filtering" — every app tunnelled, ourselves
                // included, because this arm excludes nobody explicitly. Refusing
                // is the only safe reading of "only these apps" when there are no
                // longer any. See PackageApplication.nothingApplied.
                if (applied.nothingApplied) {
                    // §5.6: no names. "Empty at the builder", not "empty" —
                    // the user selected apps; the system no longer has them.
                    Log.e(TAG, "per-app: allow list empty at the builder; refusing")
                    return TunResult.AllowListEmptied
                }
                logSkipped(applied)
            }
        }

        return builder.establish()?.let { TunResult.Established(it) } ?: TunResult.Failed
    }

    /**
     * Fix round 1, Finding 1: [DnsPlan.tunAdvertisedAddress] is only as trustworthy
     * as [art.yniyniyni.subspace.core.model.DnsValidation.isAddressLiteral], whose
     * IPv6 regex is deliberately loose (real validation is the core's, end to end)
     * and whose `hosts` values are entirely unvalidated author input. A shape that
     * regex accepts but `Builder.addDnsServer` rejects throws
     * `IllegalArgumentException` from inside [establishTun] — by which point
     * [startCore] already has a core running. Uncaught, that would escape to
     * [errorHandler] (§10.4), which publishes `Failed` but does not stop the core
     * or clear the foreground notification: a stuck notification over a runtime
     * nobody is tracking. Degrading to [DNS_SERVER] here, rather than tightening
     * the validator, keeps the containment at this one call site.
     */
    private fun Builder.addDnsServerOrFallback(dnsPlan: DnsPlan?) {
        if (addDnsServerOrFallback(dnsPlan?.tunAdvertisedAddress(), DNS_SERVER, ::addDnsServer)) {
            // §5.6: no address in this line — only that one was rejected.
            Log.w(TAG, "addDnsServer rejected the plan's address; falling back to the app default")
        }
    }

    /** §5.6: the count, never the names. A package name identifies an installed app. */
    private fun logSkipped(applied: PackageApplication) {
        if (applied.skipped > 0) {
            Log.w(TAG, "per-app: skipped ${applied.skipped} uninstalled package(s)")
        }
    }

    /**
     * §8: the app must never be routed through itself. Unlike a user-selected
     * package — where §8 says skip and continue — failing here would build §5.1's
     * loop by construction, so it is fatal.
     */
    private fun excludeSelf(builder: Builder): Boolean =
        try {
            builder.addDisallowedApplication(packageName)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "cannot exclude own package: ${e.javaClass.simpleName}")
            false
        }

    @Suppress("TooGenericExceptionCaught") // RuntimeException is the Android framework boundary here.
    private fun removeForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.e(TAG, "foreground removal failed: ${e.javaClass.simpleName}")
        }
    }

    /** @return false when the package is no longer installed. See [applyEach]. */
    // Swallowed deliberately: the only thing this exception carries is the
    // package name, and §5.6 forbids logging it. The count is the whole report.
    @Suppress("SwallowedException")
    private fun Builder.disallowOrSkip(name: String): Boolean =
        try {
            addDisallowedApplication(name)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    /** @return false when the package is no longer installed. See [applyEach]. */
    // Swallowed deliberately, as in disallowOrSkip: the exception carries only
    // the package name, which §5.6 forbids logging.
    @Suppress("SwallowedException")
    private fun Builder.allowOrSkip(name: String): Boolean =
        try {
            addAllowedApplication(name)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    @Suppress("TooGenericExceptionCaught") // RuntimeException is the Android framework boundary here.
    private fun goForeground(textRes: Int): Boolean =
        try {
            TunnelNotification.ensureChannel(this)
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
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // Framework failures only. Never log a message: notification and
            // platform errors can quote caller-controlled material.
            Log.e(TAG, "foreground lifecycle rejected: ${e.javaClass.simpleName}")
            false
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
    private fun stopTunnel(
        finalState: ConnectionState,
        expectedGeneration: Int? = null,
    ): Int? {
        val xray: XrayController?
        val fd: ParcelFileDescriptor?
        val cfg: File?
        val startId: Int

        synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != generation) return null
            // Supersede any in-flight start before taking ownership of its state.
            ++generation
            xray = controller
            fd = tunInterface
            cfg = configFile
            controller = null
            tunInterface = null
            configFile = null
            liveSession = null
            startId = activeStartId
            activeStartId = 0
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

        removeForegroundSafely()
        publish(finalState)
        return startId
    }

    /** Stops only the started-service generation that owns the completed command. */
    private fun stopStartedService(startId: Int): Boolean =
        startId > 0 && stopSelfResult(startId)

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
        val startId = stopTunnel(failure(FailureReason.Revoked, "VPN permission revoked"))
        if (startId != null) stopStartedService(startId)
    }

    override fun onDestroy() {
        // Preserve a terminal failure (notably Revoked) rather than flattening it.
        val finalState =
            synchronized(lock) {
                currentState as? ConnectionState.Failed ?: ConnectionState.Disconnected
            }
        stopTunnel(finalState)
        commandCoordinator.close()
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
            override fun disconnect() {
                commandIngress.disconnect()
            }

            override fun reapplyPerApp() {
                commandIngress.reapplyPerApp()
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

    private suspend fun connectFromCommand(
        profile: ProfileParcel,
        startId: Int,
    ) {
        val decoded = profile.toProfile()
        if (decoded == null) {
            rejectConnectFromCommand(startId, profile.rowId)
            return
        }
        startTunnel(decoded, profile.rowId, startId)
    }

    private fun reapplyPerAppFromCommand() {
        // Sample only when this command reaches the service-owned consumer.
        val session = synchronized(lock) { liveSession.takeIf { ownTunnelActive() } } ?: return
        stopTunnel(ConnectionState.Disconnected)
        startTunnel(session.profile, session.rowId, session.startId)
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
        const val FOREGROUND_LIFECYCLE_REJECTED = "ForegroundLifecycleRejected"
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
