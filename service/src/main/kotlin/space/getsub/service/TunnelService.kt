// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// TooManyFunctions counts this file's top-level helpers alongside the class's
// own, which already carries the same suppression. M7.5 split the connect-time
// guard into resolution and the reserved-tag check, which are two questions and
// so two functions; merging them back to satisfy a count would be worse code.
@file:Suppress("TooManyFunctions")

package space.getsub.service

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import space.getsub.core.data.GeoAssetRepository
import space.getsub.core.data.PerAppRepository
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.RuleSetAssets
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.toProfile
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.LatencyOptions
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.PingMode
import space.getsub.core.model.Profile
import space.getsub.core.model.Retryability
import space.getsub.core.model.StartupStage
import space.getsub.core.model.TrafficSample
import space.getsub.core.model.failure
import space.getsub.core.model.retryability
import space.getsub.core.parser.OverrideTarget
import space.getsub.core.parser.analysePassthrough
import space.getsub.core.parser.resolveOverrideTarget
import space.getsub.core.xray.ComposeFailure
import space.getsub.core.xray.ComposeResult
import space.getsub.core.xray.ConfigResult
import space.getsub.core.xray.DnsPlan
import space.getsub.core.xray.DnsPlanner
import space.getsub.core.xray.LibXrayPingApi
import space.getsub.core.xray.METRICS_RESERVED_TAG
import space.getsub.core.xray.ProxyHeadProbe
import space.getsub.core.xray.RawConfigComposer
import space.getsub.core.xray.SocketProtector
import space.getsub.core.xray.TcpProbe
import space.getsub.core.xray.TcpSocketProtector
import space.getsub.core.xray.TunnelSettings
import space.getsub.core.xray.XrayConfigGenerator
import space.getsub.core.xray.XrayController
import space.getsub.core.xray.XrayException
import space.getsub.service.log.LogCapture
import space.getsub.service.log.LogRing
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

/**
 * Decodes only this service's explicit connect request without exposing/logging
 * its payload.
 *
 * A null return covers two different intents on purpose: one with no
 * `ACTION_CONNECT` at all (always-on, boot, or the sticky restart), and an
 * `ACTION_CONNECT` whose extra is missing or unreadable. [onStartCommand] reads
 * either as "no connect request in hand" and reconciles against persisted
 * intent instead — this is the discriminator §1 of the M8 spec settled on,
 * never `intent == null`, because AOSP's always-on start carries a non-null
 * intent with a different action.
 */
@Suppress("DEPRECATION")
internal fun connectProfileFrom(intent: Intent?): ProfileParcel? {
    if (intent?.action != ACTION_CONNECT) return null
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(EXTRA_PROFILE, ProfileParcel::class.java)
    } else {
        intent.getParcelableExtra(EXTRA_PROFILE)
    }
}

/**
 * Which started-service token a live session should answer for, once a framework
 * start has landed on it and been answered with [ReconcileAction.Nothing].
 *
 * Spec §1.3/§4: every start that carries no `ACTION_CONNECT` — always-on, boot,
 * a sticky restart — records a fresh `latestStartId` in [TunnelCommandIngress]
 * *before* the reconcile it enqueues is decided. Only `Start`, `Stop` and
 * `Release` resolve that token; `Nothing` is the answer when the session is live
 * and must not be disturbed, and the session's own settlement stops with
 * [TunnelService.activeStartId] — the older token it captured at `startTunnel`.
 * Left diverged, `stopSelfResult` is called for a superseded token, returns
 * false, and the service is left running with no notification, no fd, no core
 * and nothing that would ever stop it.
 *
 * Adopting is what [TunnelService.startTunnel]'s already-active guard does for a
 * duplicate connect, for the same reason: one live session, answering for the
 * newest accepted start.
 *
 * @param sessionStartId the live session's own token, or 0 when no session holds
 *   one — in which case nothing is adopted, because a token written against no
 *   session would be resolved by whichever teardown ran next for a lifetime it
 *   never belonged to.
 * @param frameworkStartId [TunnelCommandIngress.latestStartId]. Adopted only when
 *   it is genuinely newer: framework start ids ascend, so an equal or lower value
 *   means no start has arrived since this session claimed its own, and moving
 *   backwards would hand the settlement a token `stopSelfResult` has already
 *   resolved.
 */
internal fun adoptedStartId(
    sessionStartId: Int,
    frameworkStartId: Int,
): Int = if (sessionStartId != 0 && frameworkStartId > sessionStartId) frameworkStartId else sessionStartId

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
 * Reuses `:core:parser`'s non-throwing structural read rather than Android's
 * `JSONObject`: this helper is also the connect-time guard against a missing
 * `proxy` tag, and keeping one parser for both decisions prevents them drifting.
 */
private fun existingOutboundTags(rawJson: String): Set<String> =
    analysePassthrough(rawJson).outboundTags.filterTo(mutableSetOf()) { it.isNotBlank() }

private val REQUIRED_OVERRIDE_PROTOCOLS =
    mapOf(
        "direct" to "freedom",
        "block" to "blackhole",
        "dns-out" to "dns",
    )

/**
 * The outbound tags the override branch **reserves**, which a config's balancer
 * must not also select (design §3.4).
 *
 * Deliberately the *unfiltered* set, not the set actually appended.
 * [composePassthrough] drops any stock outbound whose tag the config already
 * defines, so a config carrying its own `direct` gets none of ours — but its own
 * `direct` is still a `freedom` outbound, and a balancer selecting it leaks
 * exactly the same way. Narrowing this to what is appended would trade a
 * harmless over-refusal for a real leak.
 *
 * `dns-out` is conditional because `XrayConfigGenerator.overrideBlocks` only
 * appends it when a DNS plan is present — so a config whose balancer selects on
 * `dns-out` is refused with DNS on and accepted with it off, which is correct.
 */
internal fun reservedOverrideTags(dnsPlanPresent: Boolean): Set<String> =
    if (dnsPlanPresent) setOf("direct", "block", "dns-out") else setOf("direct", "block")

/**
 * What the app's generated rules should name for this config, decided fresh from
 * the stored bytes.
 *
 * Never stored (design §3.2): M7's one Critical was a stored verdict going stale
 * across a subscription refresh, and a stored target would go stale identically
 * the moment a refresh renames an outbound.
 */
internal fun overrideTargetFor(
    rawJson: String,
    dnsPlanPresent: Boolean,
): OverrideTarget = resolveOverrideTarget(analysePassthrough(rawJson), reservedOverrideTags(dnsPlanPresent))

/**
 * The outbound tags this app appends and therefore reserves.
 *
 * `Metrics` is conditional: a `metrics` block always registers an outbound of
 * that name (`infra/conf/metrics.go:17-20`), but no `metrics` block is emitted
 * while the breakdown is off, so reserving it unconditionally would refuse
 * configs this app can run perfectly well. M8.5 spec §2.3.
 */
internal fun reservedOutboundTags(breakdownEnabled: Boolean): Set<String> =
    buildSet {
        addAll(setOf("direct", "block", "dns-out"))
        if (breakdownEnabled) add(METRICS_RESERVED_TAG)
    }

/** Whether a config's own outbound tags already claim one this app reserves. */
internal fun collidesWithReservedTag(
    configTags: Set<String>,
    breakdownEnabled: Boolean,
): Boolean = configTags.intersect(reservedOutboundTags(breakdownEnabled)).isNotEmpty()

/**
 * Whether a reserved tag the override appends already exists with a protocol
 * that would give it different semantics.
 *
 * This is what remains of M7's connect-time guard after M7.5. Its `proxy`-tag
 * half is gone — that question is now [overrideTargetFor]'s — but the override
 * still appends `direct`, `block` and `dns-out`, and a config that already
 * defines one of those names as something else is still a config we must not
 * write rules against.
 *
 * `Metrics` (only while [breakdownEnabled]) is checked differently from the
 * other three: the override never adds a `Metrics` *outbound* to dedupe
 * against — `metrics.listen` alone makes the core register that handler — so
 * there is no compatible protocol to reuse and any existing tag of that name
 * is a collision regardless of its protocol. [REQUIRED_OVERRIDE_PROTOCOLS]
 * carries no entry for it, and the `?: true` below is what turns bare
 * existence into a refusal for exactly that tag.
 */
internal fun passthroughOverrideFailure(
    rawJson: String,
    breakdownEnabled: Boolean = false,
): ComposeFailure? {
    val protocols = analysePassthrough(rawJson).outboundProtocolsByTag
    val reservedTagIsIncompatible =
        reservedOutboundTags(breakdownEnabled).any { tag ->
            val existingProtocol = protocols[tag] ?: return@any false
            REQUIRED_OVERRIDE_PROTOCOLS[tag]?.let { it != existingProtocol } ?: true
        }
    return ComposeFailure.IncompatibleOverrideOutbound.takeIf { reservedTagIsIncompatible }
}

/**
 * Why a collapsed balancer row cannot be measured by probing one server.
 *
 * Such a row keeps the *first* member's typed projection (`ProfileRepository.import`'s
 * collapse), and both ping modes read that projection alone. Reporting its result as the
 * row's is wrong in both directions: a healthy first member hides a fleet that is failing,
 * and a dead first member — the panel's `AUTO_BALANCER` set carried eight of them on
 * 2026-09-01 — reads as the whole profile being unreachable while traffic flows fine over
 * the other members. §10.4: a result must not misdescribe what was measured, so this
 * refuses rather than reporting a number about one arbitrary destination.
 *
 * [LatencyOutcome.UNSUPPORTED] rather than a failure outcome because nothing failed —
 * the row is not a single server, which is what these probes measure. Measuring every
 * member and reporting the best is the better answer and needs `:core:parser` to expose
 * a per-member projection; recorded as a follow-up rather than done here.
 */
internal fun balancerLatencyRefusal(rawJson: String?): LatencyOutcome? =
    LatencyOutcome.UNSUPPORTED.takeIf { rawJson != null && analysePassthrough(rawJson).isBalancer }

/** Maps composition failures to the user-actionable service reason they represent. */
internal fun compositionFailureReason(reason: ComposeFailure): FailureReason =
    when (reason) {
        ComposeFailure.UnresolvableOverrideTarget,
        ComposeFailure.IncompatibleOverrideOutbound,
        -> FailureReason.PassthroughOverrideUnavailable
        ComposeFailure.NotJson,
        ComposeFailure.NoOutbounds,
        ComposeFailure.InvalidOverride,
        -> FailureReason.ConfigGenerationFailed
    }

/** This outbound literal's own `tag`, or null if it has none this can read. */
@Suppress("SwallowedException") // A literal this can't parse is treated as untagged, not a crash.
private fun String.tagOf(): String? =
    try {
        JSONObject(this).optString("tag").takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }

/**
 * The decision [TunnelService.attachTun] and [TunnelService.attachRetainedTun] share for a
 * connect that establishes its foreground notification: retire whatever retry sequence
 * preceded it. Pure — no lock, no Android call — so it is checkable outside [TunnelService],
 * which cannot be instantiated in a JVM test (`VpnService`, and this project carries no
 * Robolectric or mocking library per §10.7).
 *
 * What this function cannot pin by itself is *where* it is called from. Both call sites invoke
 * it from inside `lifecycle`, under `TunnelService.lock`, before `persist` ever suspends — that
 * placement, not this gate, is what closes the P1 race (a generation that has *committed* is
 * not necessarily still *current* once a suspension gives a newer generation room to run), and
 * it is a call-site fact this function has no way to verify. This only pins the other half of
 * that fix: cancellation must not run on a rejected transition.
 *
 * @param established the result of establishing foreground state (`goForeground`). `false`
 *   means the transition did not commit, so [retireRetry] must not run — the rejection handoff
 *   to `handleForegroundLifecycleRejection` owns whether the retry dies with the session it is
 *   about to stop, not this call.
 * @param retireRetry cancels the pending backoff job and resets the attempt counter. Run only
 *   when [established] is true.
 * @return [established], unchanged — callers use this directly as `lifecycle`'s own result.
 */
internal fun retireRetryIfEstablished(
    established: Boolean,
    retireRetry: () -> Unit,
): Boolean {
    if (established) {
        retireRetry()
    }
    return established
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
 *  - [lock] guards every field below and every state publication, including
 *    [broadcastTrafficSample] — [trafficLoop]'s per-second push shares
 *    [callbacks] with [publishLocked], so it takes the same lock for the same
 *    reason. `RemoteCallbackList` is not safe for concurrent broadcast —
 *    `beginBroadcast()` throws if one is already in progress, and that throw
 *    landing inside teardown would abandon the TUN fd, which is §5.4's
 *    wedged-until-reboot outcome.
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

    /**
     * Spec §1.2's `wanted` writes that are conditional on still owning the
     * session — see [SessionIntentGate] for the race it closes.
     *
     * Injected, not built in [onCreate] like [connectionRecorder]: it is a
     * `:bg`-process singleton ([ServiceModule]), shared by every instance of this
     * service the process ever hosts. A clear that outlives this instance —
     * [settleTerminalFailure]'s `NonCancellable` `persist` — must check its token
     * against the gate the *next* instance's connect moves, and a per-instance
     * gate is one no later connect ever touches. `internal`, not `private`,
     * because Hilt's generated injector assigns it.
     */
    @Inject
    internal lateinit var sessionIntent: SessionIntentGate

    /**
     * §11 row 7. One per `:bg` process, so a terminal failure survives *this*
     * instance — see [TerminalStateMemory], and [ServiceModule] for why the
     * `@Singleton` scope is the whole point of the binding.
     */
    @Inject
    internal lateinit var terminalState: TerminalStateMemory

    // Built in onCreate, once its data collaborators above are injected. Same
    // suspend-lambda indirection as connectionRecorder, for the same reason —
    // see RoutingResolver's KDoc.
    private lateinit var routingResolver: RoutingResolver

    // Built in onCreate for the same reason routingResolver is, and reading the
    // selection at resolve time rather than at construction — see PerAppResolver.
    private lateinit var perAppResolver: PerAppResolver

    /**
     * Spec §5.1/§5.2. Built in onCreate, once `applicationContext` is available —
     * calling a `Context` method from a field initialiser runs before
     * `attachBaseContext`, which is the same reason [connectionRecorder] and its
     * neighbours above are `lateinit var` rather than initialised inline.
     *
     * Registered for as long as the session is *wanted*, driven by
     * [SettingsRepository.tunnelSessionWanted] in [onCreate] — not by connect/
     * disconnect — and stopped explicitly in [onDestroy]. See that collector's
     * comment for why this cannot simply follow [ownTunnelActive].
     */
    private lateinit var networkMonitor: NetworkMonitor

    /**
     * The collector launched in [onCreate] that drives [networkMonitor]'s
     * start/stop from [SettingsRepository.tunnelSessionWanted]. Captured so
     * [onDestroy] can cancel it *before* anything else — see that cancellation
     * for why a plain `scope.cancel()` at the end of teardown is not early
     * enough on its own.
     */
    private var networkMonitorJob: Job? = null

    /**
     * §10.4: anything escaping a coroutine on [scope] must still produce a
     * legible state. Without this the failure is invisible — spec §0.3, `:bg`
     * logging never reaches logcat — and the UI sits on `Connecting` until it
     * notices binder death.
     *
     * **It publishes and does not tear down, and that is deliberate.** An earlier
     * version routed this through [stopTunnel] to make the published `Failed`
     * true. That was worse than the problem it solved:
     * [FailureReason.CoreStartFailed] is `Retryable` (§2.2), and every other path
     * in this service settles it as `Reconnecting` with the TUN retained under
     * fail-closed — so tearing down here closed the fd, released §6.1's kill
     * switch and armed no retry, for a reason the rest of the service recovers
     * from. [stopTunnel] also defaults `expectedGeneration` to null, so it ended
     * whichever session happened to be current rather than the one that crashed.
     *
     * What that leaves is a real gap, recorded rather than papered over: a crash
     * landing after `Connected` leaves a terminal `Failed` standing over a live
     * tunnel — §5.5's lying UI. Closing it properly means settling *retryably*
     * from a non-suspend handler that does not know the failing generation, which
     * is a design worth making with a device in the loop rather than inferring at
     * a milestone's tail. M8.5 owns it.
     *
     * The one consequence that could not wait is closed at its own site instead:
     * everything downstream reads `Failed` as a session that has already settled,
     * so `reconcile` answers [ReconcileAction.Release] for it and
     * [releaseServiceWithoutPublishing] would take the ongoing notification off a
     * `VpnService` still carrying traffic (§9 requires one for the life of the
     * tunnel). That function refuses while a TUN is attached.
     *
     * Session intent is deliberately **not** cleared: a crash is not the user
     * asking to disconnect, and spec §1.2 names the three sites that clear it.
     *
     * What can reach here is bounded and every member of it owns the session: the
     * start sequence, the `tunnelSessionWanted` collector's body (see
     * [collectSessionIntentForMonitor] — [NetworkMonitor.start]'s registration
     * failure rethrows deliberately), the command coordinator's consumer loop, the
     * backoff timer, and the two launched intent clears. A measurement cannot:
     * [LatencyRunner] catches broadly on purpose so it never publishes a tunnel
     * failure for a failed probe.
     */
    private val errorHandler =
        CoroutineExceptionHandler { _, e ->
            // §5.6: the class name only, never the message — a Room or libXray
            // error quotes the config straight back.
            Log.e(TAG, "coroutine on the service scope crashed: ${e.javaClass.simpleName}")
            publish(failure(FailureReason.CoreStartFailed, e.javaClass.simpleName))
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private val callbacks = RemoteCallbackList<ITunnelCallback>()

    /**
     * Spec §3.3: on-disk ring a session's log is captured into, and the
     * capture that reads logcat, redacts, and appends to it. Tied to session
     * lifetime — [logCapture] starts when the service enters foreground for a
     * connect ([startTunnel]) and stops last in [stopTunnel], after every
     * phase that logs.
     *
     * `by lazy`, not an eager initialiser: [filesDir] is a `Context` method,
     * and a field initialiser runs before `attachBaseContext()` — the same
     * reason [foreignVpn] just below is `by lazy` rather than built inline.
     */
    private val logRing by lazy { LogRing(File(filesDir, LOG_DIR_NAME)) }
    private val logCapture by lazy { LogCapture(logRing) }

    /**
     * Spec §1.5: samples [Tun2Socks.stats] once a second while a session is up
     * and pushes each accumulated total through [broadcastTrafficSample]. Tied
     * to session lifetime like [logCapture] just above: started once the
     * session reaches [ConnectionState.Connected] (both [attachTun] and
     * [attachRetainedTun]'s `lifecycle` blocks — [start] is idempotent, so the
     * retained-TUN restart's re-settle is a no-op), stopped in [stopTunnel] at
     * [space.getsub.service.log.TeardownStep.StopTrafficSampler]'s position,
     * immediately before [logCapture] stops.
     */
    private val trafficLoop =
        TrafficSamplerLoop(
            scope = scope,
            read = { Tun2Socks.stats() },
            emit = ::broadcastTrafficSample,
        )

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

    /**
     * The **planned** DNS address recorded for [tunInterface] (spec §5.2, lever
     * 1), or null when there is no interface.
     *
     * Planned, not accepted, and the difference is real: this is
     * [advertisedTunDnsAddress]'s answer for the plan the interface was built
     * from, and if `Builder.addDnsServer` rejected that literal then
     * `addDnsServerOrFallback` advertised [DNS_SERVER] while this field went on
     * naming the rejected one. So this is not a record of what the interface is
     * doing, and a future reader must not compare it against one.
     *
     * The asymmetry is deliberate and safe in exactly one direction — see
     * [advertisedTunDnsAddress], which argues it: rejection is a function of the
     * address, so two plans resolving to the same planned address resolve to the
     * same advertised one. [retainedTunKeepsAdvertisedDns] can therefore rebuild
     * an interface it did not need to, and can never keep one it should have
     * rebuilt, which is the only direction that leaks.
     *
     * One piece of state with [tunInterface], under the same [lock] and with the
     * same lifetime: written where the fd is adopted, cleared everywhere the fd
     * is. A restart that keeps the interface cannot change what it advertises,
     * so this is what [restartCoreRetainingTun] compares the newly resolved plan
     * against before deciding whether keeping it is still safe.
     */
    private var tunAdvertisedDns: String? = null
    private var configFile: File? = null
    private var generation = 0
    private var currentState: ConnectionState = ConnectionState.Disconnected

    /**
     * The [SessionIntentGate] token the live session owns — recorded by
     * [startTunnel] under [lock], ahead of its already-active guard, and read
     * back by [settleTerminalFailure] for its conditional intent clear.
     *
     * Recorded on both of [startTunnel]'s branches, and only one of them bumps
     * `generation`. The fold branch — a second connect absorbed into the live
     * session instead of starting its own — moves this field with no bump, on
     * purpose (see that branch). So this is not paired with the generation: it
     * names whichever accepted connect the live session currently answers for.
     *
     * Read back rather than read live from the gate at clear time, because the
     * window this closes is exactly the one in which a newer connect has already
     * written `wanted = true` (moving the gate's token) and has not yet reached
     * [startTunnel] — see [SessionIntentGate] for why the generation counter
     * cannot be the guard.
     *
     * Not reset by [stopTunnel] or [restartCoreRetainingTun]. A retained-fd
     * restart is the same session under a new generation and keeps its owner.
     * After a [stopTunnel] nothing reads the stale value: a settlement reads this
     * only once `settle` has confirmed its generation is current, and every
     * generation that can settle came from [startTunnel], which records a fresh
     * token first, or from [restartCoreRetainingTun], which keeps the live one.
     */
    private var sessionIntentToken = 0

    /**
     * Spec §2.4's retry timer — a plain coroutine delay on [scope], never an
     * alarm or a `WorkManager` job, so it cannot outlive the session it belongs
     * to. Cancelled on every path where that session ends — see
     * [cancelBackoffRetry] for the enumerated call sites, and for why
     * missing one corrupts a later terminal state rather than merely
     * wasting a wakeup.
     */
    private var backoffJob: Job? = null

    /**
     * Spec §2.3/§2.4. Reset on a committed [ConnectionState.Connected] (see
     * [attachTun]) and on every [stopTunnel] — an explicit disconnect, revoke,
     * or a terminal failure all start the next retry sequence counting from
     * zero. Advanced only by [settleRetryableFailure].
     */
    private val reconnectAttempts = ReconnectAttemptCounter()

    /**
     * The profile the live session is running, so a per-app change can rebuild
     * the tunnel without :main re-supplying one (§5.5: what is connected is this
     * process's fact, not the UI's).
     *
     * Set fresh by [startTunnel], and moved by [restartCoreRetainingTun] when a
     * reconcile restart follows the active profile onto a different row (spec
     * §1.1) — `startId` kept, the row and profile replaced. It has to name what
     * the core is actually running: before the restart updated it, a restart
     * onto another server left this on the old one, and the next per-app
     * reapply silently reconnected there.
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
            // A connect [TunnelCommandIngress] refused before it could reach
            // [connectFromCommand]: no `want()` ran for it, so the token to clear
            // with is the gate's current one, read here on the coordinator.
            rejectConnect = { startId, rowId ->
                rejectConnectFromCommand(startId, rowId, sessionIntent.currentToken())
            },
            disconnect = { startId ->
                // Spec §1.2: one of the three events that clear session intent.
                // Through the gate like every other intent write in `:bg`. The
                // token is read on the coordinator, where the only caller of
                // `want()` also runs, so nothing can move it before the clear:
                // an explicit disconnect is never refused.
                val ownedToken = sessionIntent.currentToken()
                sessionIntent.clearIfOwned(ownedToken)
                stopTunnel(ConnectionState.Disconnected)
                stopStartedService(startId)
            },
            reapplyPerApp = ::reapplyPerAppFromCommand,
            reconcile = ::reconcileNow,
            observeConnect = ::observeConnectFromCommand,
        )
    private val commandIngress = TunnelCommandIngress(commandCoordinator::enqueue)

    override fun onCreate() {
        super.onCreate()
        // §11 row 7. A terminal failure published by the *previous* instance is
        // this instance's starting state: on device the service object is replaced
        // while the process survives, and seeding the field's default here is what
        // made a revoke read as `Disconnected` to anyone who opened the app after
        // the fact. Seeding the remembered failure makes this instance answer the
        // binder exactly as the destroyed one would have.
        synchronized(lock) { currentState = terminalState.seedState() }
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
        networkMonitor =
            NetworkMonitor(
                context = applicationContext,
                onChanged = { network ->
                    // Spec §5.2. Never called before M8. Not cosmetic: this is what makes
                    // the VPN report correct metered-ness and transport to apps querying
                    // through it — including this app's own geoRefreshOnMetered and
                    // pingOnLaunchMetered, which until now read whatever the platform
                    // inferred in its absence.
                    setUnderlyingNetworks(arrayOf(network))
                    commandCoordinator.enqueue(TunnelCommand.Reconcile(ReconcileTrigger.NetworkChanged))
                },
                onNetworkLost = {
                    commandCoordinator.enqueue(TunnelCommand.Reconcile(ReconcileTrigger.NetworkLost))
                },
            )
        // Spec §5.1: registered for as long as the session is *wanted*, not while
        // connected — fail-closed means sitting with no network and waiting, and
        // this callback is the thing being waited on. Collecting the persisted
        // flow (rather than toggling this at every connect/disconnect call site)
        // also covers a sticky restart: `:bg` dying and coming back with a still-
        // wanted session re-registers the callback without a fourth call site to
        // remember. `distinctUntilChanged` avoids a pointless re-register/
        // unregister when an unrelated settings write touches the same Room table.
        //
        // The `Job` is captured, not discarded, so `onDestroy` can cancel this
        // collector before doing anything else: with no suspension point inside
        // the `if`/`else` below, a plain `scope.cancel()` at the end of teardown
        // does not stop a `true` emission landing between an explicit
        // `networkMonitor.stop()` and that final cancel from calling `start()`
        // again and re-registering a callback nothing is left to unregister.
        networkMonitorJob = scope.launch { collectSessionIntentForMonitor() }
    }

    /**
     * The `tunnelSessionWanted` collector, with failure handling for **the Room
     * read only**.
     *
     * This collector is long-lived and shares [scope] with the start sequence,
     * whose [errorHandler] publishes `Failed(CoreStartFailed)` — ungated by
     * generation and performing no teardown. So a Room failure *here* used to be
     * reported as a start-sequence crash **over a live `Connected` session**, and
     * `reconcile` never restarts anything from `Failed`: the session would sit
     * there with the UI saying failed, the tunnel up, and nothing retrying.
     * §5.5's lying UI, reached by a route M8 opened when it put this coroutine on
     * that scope.
     *
     * Handling it here rather than widening [errorHandler] keeps that handler
     * doing the one job its KDoc describes. A failure in this collector is not a
     * start sequence crashing, and must not be published as one.
     *
     * **`catch` rather than a `try` around the whole thing, and the difference is
     * load-bearing.** `Flow.catch` is transparent to downstream: it sees what the
     * flow throws and *not* what this collector's own body throws. The body calls
     * [NetworkMonitor.start], whose registration failure is a deliberate rethrow
     * — `.onFailure { thread.quitSafely() }.getOrThrow()`, with its own comment
     * saying it must not be swallowed, because [errorHandler] turning it into a
     * legible failure is the existing behaviour. A `try` wrapping the `collect`
     * caught that too, and what replaced the legible failure was one `Log.e` line
     * in a process whose logging never reaches logcat (spec §0.3) — invisible by
     * construction, with the network callback left unregistered for the life of
     * the service. That callback is the only thing that can resume a no-network
     * `Reconnecting` session, because §2.4 deliberately arms no timer without a
     * network.
     *
     * `CancellationException` is rethrown explicitly rather than relying on the
     * operator's own handling of it: cancellation is how [onDestroy] stops this
     * collector, and swallowing it would leave the collector's caller running.
     * §5.6: the exception's class name only, never its message, which can quote a
     * row.
     */
    private suspend fun collectSessionIntentForMonitor() {
        settingsRepository.tunnelSessionWanted
            .distinctUntilChanged()
            .catch { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "session-intent collector failed: ${e.javaClass.simpleName}")
            }
            .collect { wanted ->
                if (wanted) networkMonitor.start() else networkMonitor.stop()
            }
    }

    /**
     * §11 and §5.4.
     *
     * **`START_STICKY`, and a null intent is a legitimate entry point.** This
     * previously returned `START_NOT_STICKY` because a null-intent start had no way
     * to know what to connect to, so resurrection really was the bug. Spec §1
     * removes that premise: session intent is persisted, so a null intent means
     * *read the intent and reconcile*, and a restart with nothing wanted stops
     * immediately — the same outcome, reached by asking rather than by refusing.
     *
     * Always-on VPN and the boot receiver both arrive this way, and so does a sticky
     * restart after `:bg` is killed. That last one is what keeps fail-closed honest:
     * without it, "hold the TUN while the session is wanted" would silently mean
     * "until the process dies".
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Spec §6.3: the notification's disconnect action, handled before the
        // connect decode below — never an `intent == null` check, the same
        // discriminator rule R6 states for `connectProfileFrom`. The disconnect
        // path already clears session intent (Task 7 Step 6), so nothing extra
        // is needed here to stop the reconnect loop.
        //
        // Enqueued straight onto `commandCoordinator`, deliberately bypassing
        // `commandIngress`'s startId tracking: this `startId` is the platform's
        // own, freshest token for *this* call, which is exactly what
        // `stopStartedService` needs to resolve via `stopSelfResult`. It is not
        // recorded as `commandIngress.latestStartId()`, so a reconcile that
        // follows and reads that field sees whatever it held before this tap —
        // harmless, since the disconnect above already cleared intent to
        // `wanted=false`, so any such reconcile resolves to `Stop` against an
        // already-stopped generation. Do not "fix" this into going through
        // `commandIngress` without re-checking that reasoning.
        if (intent?.action == ACTION_DISCONNECT) {
            commandCoordinator.enqueue(TunnelCommand.Disconnect(startId))
            return START_STICKY
        }
        val request = connectProfileFrom(intent)
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val testObserver = testConnectObserverFrom(intent, debuggable)
        // R6: branch on the absence of an explicit connect request
        // (connectProfileFrom's own discriminator), never on `intent == null` —
        // always-on VPN starts this with a non-null intent carrying no
        // ACTION_CONNECT, and a null-intent check would silently ignore it.
        val accepted =
            if (request != null) {
                // ACTION_CONNECT: TunnelClient.connect uses startForegroundService,
                // and startTunnel answers that contract inside
                // runAfterForegroundEstablished. Nothing extra is owed here.
                commandIngress.started(request, startId, testObserver)
            } else {
                // Everything that is not an explicit connect — the boot receiver,
                // always-on, and a sticky restart — reaches the service here, and
                // BootReceiver arrives via startForegroundService. That arms the
                // platform's ~10 s startForeground contract, and until now the only
                // code that answered it was startTunnel. Every other outcome left it
                // unanswered: an active profile row that has been deleted or no
                // longer decodes makes startFromRow call stopTunnelAndService, and a
                // reconcile answering Nothing or Stop never starts a tunnel at all —
                // so a device with boot autostart on and a stale active row would
                // take a ForegroundServiceDidNotStartInTimeException on every boot.
                //
                // Answered here, before the queue and before any Room read, because
                // the slow-cold-boot case cannot be fixed per-branch: the contract
                // can expire while the reconcile is still waiting on the database.
                //
                // **The text is chosen from the current state, never a fixed
                // "Connecting…".** TunnelNotification.ID is one shared id, so this
                // call does not add a notification — it overwrites whatever the
                // live session is showing. An earlier revision passed
                // notification_connecting unconditionally and claimed the reconcile
                // would put it back; that is false on every arm which answers
                // Nothing, and the worst case is the one §6.4 rests on: a
                // fail-closed Reconnecting session showing "traffic is blocked"
                // becomes a permanent "Connecting…" over a session that is still
                // holding traffic. §6.4 defends defaulting fail-closed *on* entirely
                // on §6.3's notification telling the truth.
                //
                // Re-asserting the current state's own text instead is what makes
                // this call safe on a live session, and answering the contract
                // unconditionally is what keeps it safe on a dead one: a boot start
                // can reach a service that always-on already brought up (the device
                // record measured BOOT_COMPLETED arriving ~4m23s after boot), and
                // whether the platform re-arms its ~10 s timeout for a second
                // startForegroundService on an already-foreground service is not
                // documented anywhere in docs/agent/research/. §10.5: this does not
                // guess — it answers the contract either way.
                //
                // §14.1/§9: systemExempted is not among the six types Android 15
                // forbids a BOOT_COMPLETED receiver to launch (dataSync, camera,
                // mediaPlayback, phoneCall, mediaProjection, microphone), and the
                // general BOOT_COMPLETED exemption from the background-start
                // restriction still applies to it — sourced in
                // docs/agent/research/2026-09-07-always-on-and-boot-fgs.md, Q3.
                //
                // A rejection is logged and not fatal: the reconcile below may well
                // be about to stop or release the service anyway, and failing the
                // start here would turn a recoverable boot into a crash.
                if (!goForeground(entryForegroundText())) {
                    Log.w(TAG, "foreground contract not answered on a reconcile start")
                }
                commandIngress.reconcile(ReconcileTrigger.NullIntentStart, startId)
            }
        if (!accepted) {
            Log.w(TAG, "connect request dropped: service command queue closed")
            stopStartedService(startId)
        }
        return START_STICKY
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

    // §1.2: the three rejection functions below — [rejectConnectFromCommand],
    // [rejectInitialForegroundLifecycle] and [handleForegroundLifecycleRejection]
    // — each publish a terminal `Failed` and stop without going through
    // [settleTerminalFailure], so none of them inherits its `persist` block and
    // each has to clear session intent itself. Left set, the next always-on
    // bind, boot start or sticky restart reads `wanted = true`, reconciles, and
    // silently connects a session the user never got: exactly the resurrection
    // §1 exists to make impossible.
    //
    // Each clears through [SessionIntentGate.clearIfOwned], never the
    // repository: a direct write can land after a newer connect's `want()` and
    // wipe the intent of a session that is live and wanted. What differs is only
    // where the token comes from, and each function says.

    /**
     * A connect whose profile would not decode.
     *
     * [intentToken] is the gate's token as the command coordinator holds it —
     * the value [connectFromCommand]'s own `want()` just returned, or, for a
     * connect [TunnelCommandIngress] refused before it reached
     * [connectFromCommand], the gate's current one. The only caller of `want()`
     * runs on the coordinator too, so nothing can move it before the clear, and
     * this clear is never refused.
     *
     * Deliberately not the token [stopTunnel] hands back. On the
     * [connectFromCommand] path that belongs to the session that was live
     * *before* this connect, and this connect's `want()` has already moved the
     * gate past it — so clearing with it would be refused, and a connect that
     * could never start would be left wanted.
     */
    private suspend fun rejectConnectFromCommand(
        startId: Int,
        rowId: Long,
        intentToken: Int,
    ) {
        Log.e(TAG, "connect request refused: ProfileDecodeFailed")
        val failed = failure(FailureReason.ProfileDecodeFailed, "connect request could not be decoded")
        stopTunnel(failed)
        // Before stopStartedService, not after: that call can destroy the
        // service, and onDestroy cancels [scope]. A clear left until afterwards
        // races that cancellation and can simply not land, leaving wanted = true
        // and the always-on resurrection this exists to prevent.
        sessionIntent.clearIfOwned(intentToken)
        stopStartedService(startId)
        connectionRecorder.record(rowId, failed)
    }

    /**
     * [startTunnel]'s synchronous rejection callback, for when the initial
     * foreground notification is refused.
     *
     * Clears with the token [stopTunnel] captured in the lock region that proved
     * [gen] still owned the tunnel — this attempt's own, which [startTunnel]
     * recorded moments earlier on this same call. The clear itself runs in a
     * coroutine launched off the coordinator, so a token read when it runs could
     * already belong to a connect queued behind this one. This one cannot.
     */
    private fun rejectInitialForegroundLifecycle(
        gen: Int,
        rowId: Long,
    ) {
        val failed = failure(FailureReason.CoreStartFailed, FOREGROUND_LIFECYCLE_REJECTED)
        val stopped = stopTunnel(failed, expectedGeneration = gen) ?: return
        // Not made `suspend` to await these: this is [runAfterForegroundEstablished]'s
        // synchronous rejection callback. The existing fire-and-forget shape the
        // spec-D4 write already uses is extended to carry the intent clear rather
        // than opening a second launch — both belong to the same settled outcome and
        // ordering them against each other costs nothing.
        scope.launch {
            sessionIntent.clearIfOwned(stopped.intentToken)
            stopStartedService(stopped.startId)
            connectionRecorder.record(rowId, failed)
        }
    }

    /**
     * [TerminalOutcome.settleHandlingLifecycleRejection]'s rejection path, for
     * both [attachTun] and [attachRetainedTun].
     *
     * Clears with the token [stopTunnel] captured in the lock region that proved
     * [gen] still owned the tunnel: the session being ended, including any
     * connect folded into it since it started (see [startTunnel]'s fold branch).
     * [attachTun]'s call runs off the command coordinator, so a new connect can
     * be accepted while this function runs; its `want()` moves the gate past the
     * captured token, and this clear is refused rather than ending the session
     * that connect is starting.
     */
    private suspend fun handleForegroundLifecycleRejection(
        gen: Int,
        rowId: Long,
    ) {
        val failed = failure(FailureReason.CoreStartFailed, FOREGROUND_LIFECYCLE_REJECTED)
        val stopped = stopTunnel(failed, expectedGeneration = gen) ?: return
        sessionIntent.clearIfOwned(stopped.intentToken)
        stopStartedService(stopped.startId)
        connectionRecorder.record(rowId, failed)
    }

    // ── State publication ───────────────────────────────────────────────────

    private fun publish(next: ConnectionState) {
        synchronized(lock) { publishLocked(next) }
    }

    private fun publishLocked(next: ConnectionState) {
        currentState = next
        // §11 row 7: hand the terminal state to the process-scoped memory so the
        // *next* instance can seed from it. Recording every state, not only
        // failures, is deliberate — [TerminalStateMemory.record] clears itself on
        // anything non-terminal, which is what makes a stale revoke impossible
        // without a timestamp.
        terminalState.record(next)
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
     * Spec §1.5: pushes one accumulated sample to every bound `:main` over the
     * existing [ITunnelCallback], reusing [callbacks] rather than a second
     * `RemoteCallbackList`.
     *
     * Takes [lock] for the same reason [publishLocked] does: `RemoteCallbackList`
     * is not safe for concurrent broadcast, and [trafficLoop] calls this from its
     * own coroutine — a call arriving mid-[publishLocked] would hit
     * `beginBroadcast()`'s reentrancy throw. [TrafficSampleParcel] carries no free
     * text, so unlike [publishLocked] there is nothing here for §5.6 to redact.
     */
    private fun broadcastTrafficSample(sample: TrafficSample) {
        synchronized(lock) {
            val parcel = TrafficSampleParcel.from(sample)
            val count = callbacks.beginBroadcast()
            repeat(count) { i ->
                try {
                    callbacks.getBroadcastItem(i).onTrafficSample(parcel)
                } catch (e: android.os.RemoteException) {
                    Log.w(TAG, "traffic callback dropped: ${e.javaClass.simpleName}")
                }
            }
            callbacks.finishBroadcast()
        }
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
    /**
     * @param intentToken the [SessionIntentGate] token this session owns: the
     *   value `want()` returned for the connect that led here
     *   ([connectFromCommand]), or the live session's own for a per-app rebuild
     *   that changes no intent ([reapplyPerAppFromCommand]). Handed in rather
     *   than read from the gate here, so this cannot record a token some other
     *   connect minted.
     */
    private fun startTunnel(
        profile: Profile,
        rowId: Long,
        startId: Int,
        intentToken: Int,
    ) {
        val gen =
            synchronized(lock) {
                // Recorded before the guard below, so both outcomes take it. If a
                // second connect is folded into the live session rather than
                // starting its own, that session now owns the newest accepted
                // intent — and the settlement of the start already in flight
                // must be allowed to clear it, because no separate session was
                // ever created for it to belong to. Leaving the field behind on
                // that branch would refuse a clear that spec §1.2 requires, and
                // a terminal failure would settle with `wanted = true` still
                // persisted.
                sessionIntentToken = intentToken
                // §5.5 makes this service the source of truth, so it cannot rely
                // on the UI to prevent a second connect. Without this guard the
                // previous TUN fd leaks and the old core runs on unreferenced.
                //
                // Reconnecting is allowed through alongside Disconnected/Failed:
                // it means a retryable failure already ran settleRetryableFailure's
                // cleanup (controller/configFile/liveSession all null) and is
                // waiting out its backoff. Refusing here would silently swallow
                // every retry the moment BackoffElapsed fires it — fix round 1,
                // Finding 1's core bug.
                //
                // It does NOT mean no TUN is running. With the kill switch on —
                // the default — settleRetryableFailure deliberately keeps
                // tunInterface open as the blackhole (§6.1), so this guard lets
                // through the one state that can still hold a live fd. That is
                // safe only because [attachTun] closes the retained fd before
                // adopting its own; this comment previously claimed "no core or
                // TUN running", and that belief is what hid the leak the final
                // review found (C1). Do not restore it.
                if (currentState !is ConnectionState.Disconnected &&
                    currentState !is ConnectionState.Failed &&
                    currentState !is ConnectionState.Reconnecting
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
                // Spec §3.3: capture starts here, once the service has actually
                // entered foreground and before the start sequence below runs —
                // not inside it, so a session that never reaches resolveAndStartCore
                // is still captured.
                logCapture.start()
                scope.launch {
                    val started = resolveAndStartCore(gen, profile, rowId) ?: return@launch
                    when (val outcome = attachTun(gen, started.xray, started.ports, started.dnsPlan, rowId)) {
                        TunAttachOutcome.Settled -> Unit
                        // §8's refusal is published *here*, not inside
                        // [attachTun], and this is the call site that makes it
                        // the right answer: a fresh connect with nothing left to
                        // allow is the terminal, user-actionable failure it has
                        // always been. Nothing about this path changes — the same
                        // reason, from the same core-stopping helper, as before.
                        is TunAttachOutcome.NoPerAppPlan ->
                            failAfterCore(
                                gen,
                                started.xray,
                                FailureReason.PerAppAllowListEmpty,
                                outcome.detail,
                                rowId,
                            )
                    }
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
            val breakdownEnabled = settingsRepository.perTagBreakdown.first()
            return when (val composed = composePassthrough(rawJson, settings, routing, dnsPlan, breakdownEnabled)) {
                is ComposeResult.Ok -> ConfigJsonOutcome.Ok(composed.json, runsAsWritten = true)
                // Distinct from PassthroughRejectedAtConnect, which startCore uses when the
                // *core* refuses a well-formed config (§10.4): this means composition itself
                // failed — a condition import already screens for structurally, so reaching
                // it here is an environment or data change, not a core verdict.
                is ComposeResult.Failed ->
                    ConfigJsonOutcome.Failed(
                        compositionFailureReason(composed.reason),
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
    @Suppress("ReturnCount") // One early return per distinct refusal, same reasoning as startCore's own.
    private fun composePassthrough(
        rawJson: String,
        settings: TunnelSettings,
        routing: RoutingResolution,
        dnsPlan: DnsPlan?,
        breakdownEnabled: Boolean,
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
                passthroughOverrideFailure(rawJson, breakdownEnabled)?.let { return ComposeResult.Failed(it) }
                val target =
                    when (val resolved = overrideTargetFor(rawJson, dnsPlanPresent = dnsPlan != null)) {
                        is OverrideTarget.Resolved -> resolved
                        // A6 and the ambiguity gates are ours to catch; a balancer
                        // tag we invented would be caught by `testXray` anyway (A3),
                        // but a config we cannot resolve must never reach a rule
                        // emitter — which `Resolved` makes a type error rather than
                        // a discipline.
                        is OverrideTarget.Unresolvable ->
                            return ComposeResult.Failed(ComposeFailure.UnresolvableOverrideTarget)
                    }
                val existingTags = existingOutboundTags(rawJson)
                XrayConfigGenerator.overrideBlocks(settings, target)
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

        /**
         * §8 yielded no plan to build with, and **nothing has been published or
         * stopped**.
         *
         * Deliberately not a [Failed]: which failure this is — or whether it is a
         * failure at all — depends on who asked. A fresh connect publishes
         * [FailureReason.PerAppAllowListEmpty] for it; a rebuild keeps the
         * interface it already has. See [TunAttachOutcome.NoPerAppPlan].
         */
        data class NoPlan(val detail: String) : PerAppGateResult
    }

    /**
     * How [attachTun] ended, for the one question its two callers answer
     * differently.
     *
     * [attachTun] publishes every failure it can name on its own. The single
     * exception is §8's gate: the same condition is a terminal, user-visible
     * refusal on a fresh connect and a *non-event* on a network-change rebuild,
     * which must keep running over the interface it already has rather than end
     * the session. Returning that one case instead of publishing it is what lets
     * each caller decide, without creating a second code path that can publish a
     * failure.
     */
    private sealed interface TunAttachOutcome {
        /**
         * This attempt is over and nothing is owed: it committed `Connected`, it
         * was superseded, or it published its own failure through [failStart].
         */
        data object Settled : TunAttachOutcome

        /**
         * §8 produced no usable plan. **Nothing was published, nothing was
         * stopped, and no interface was established** — the core [startCore] left
         * running is still running, and [tunInterface] is untouched.
         *
         * Reached two ways, and neither is answerable before the attempt starts:
         * allow-list mode with nothing selected ([builderPlan] returns null), and
         * an allow list whose every package has since been uninstalled, which
         * only a real `VpnService.Builder` discovers
         * ([TunResult.AllowListEmptied]).
         */
        data class NoPerAppPlan(val detail: String) : TunAttachOutcome
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
            // Returned rather than published: on a rebuild this must not end the
            // session — see [TunAttachOutcome.NoPerAppPlan]. The core is left
            // running on purpose, because the rebuild goes on to use it.
            PerAppGateResult.NoPlan("allow-list mode with no application selected")
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

    /** How [establishOrFail] ended. Mirrors [TunAttachOutcome]'s split, one step lower. */
    private sealed interface EstablishOutcome {
        data class Established(val fd: ParcelFileDescriptor) : EstablishOutcome

        /** [failStart] has already run. */
        data object Settled : EstablishOutcome

        /**
         * The allow list emptied out at the builder. Nothing published, and **no
         * fd exists** — [establishTun] returns this before it calls
         * `Builder.establish()`, so there is nothing here for §5.4 to close.
         */
        data class NoPerAppPlan(val detail: String) : EstablishOutcome
    }

    /**
     * [establishTun] plus its §10.4 failure, kept out of [attachTun] so that
     * function stays under detekt's length threshold.
     *
     * [TunResult.AllowListEmptied] is deliberately **not** published here: it is
     * §8's gate arriving one step later than [resolvePerApp]'s, and it is the
     * same non-event on a rebuild. See [TunAttachOutcome.NoPerAppPlan].
     */
    private suspend fun establishOrFail(
        gen: Int,
        xray: XrayController,
        plan: BuilderPlan,
        dnsPlan: DnsPlan?,
        rowId: Long,
    ): EstablishOutcome =
        when (val result = establishTun(plan, dnsPlan)) {
            is TunResult.Established -> EstablishOutcome.Established(result.fd)
            // §10.4: the same reason an empty selection gets, because after the
            // skipping there is genuinely no application left to allow — but
            // whether that is fatal is the caller's call, not this function's.
            TunResult.AllowListEmptied ->
                EstablishOutcome.NoPerAppPlan("every allow-listed application is no longer installed")
            TunResult.Failed -> {
                failAfterCore(gen, xray, FailureReason.TunEstablishFailed, "establish() returned null", rowId)
                EstablishOutcome.Settled
            }
        }

    /**
     * Builds the TUN interface and hands its fd to tun2socks.
     *
     * Every failure stops the core [startCore] left running — §5.4: a
     * half-started tunnel must not survive as a live runtime with nothing left
     * to service. The fd is a separate question, and this function does not
     * answer it once the fd has been adopted into [tunInterface]: from that
     * point the failure policy [failStart] reaches owns its lifetime, which is
     * what lets §6.1's kill switch retain it. See the `Tun2Socks.start` failure
     * block below for the exactly-once argument.
     *
     * §5.4 across the [TunAttachOutcome.NoPerAppPlan] returns specifically:
     * **neither of them has established anything.** The first happens before
     * `Builder.establish()` is reached at all; the second happens inside
     * [establishTun], which returns [TunResult.AllowListEmptied] *before* its own
     * `establish()` call. So no fd exists to close on either, and [tunInterface]
     * is whatever it already was — closed later by exactly the sites that closed
     * it before: the connect caller's [failStart] settlement, or, on a rebuild,
     * whichever settlement or [stopTunnel] ends the session that kept it.
     *
     * @return [TunAttachOutcome.NoPerAppPlan] when §8 yielded no plan, in which
     *   case **this function has published nothing and stopped nothing** and the
     *   caller owes a decision; [TunAttachOutcome.Settled] otherwise.
     */
    // Same reasoning as startCore: each early return is a distinct §10.4 failure
    // or a supersede check, and collapsing them would hide which one fired.
    //
    // LongParameterList: [ports] bundles socksPort/httpPort rather than taking
    // them separately, reusing the pairing [startCore] already established —
    // one fewer place to mismatch which index is which, and it keeps this
    // signature at five rather than six now that [dnsPlan] joined it.
    @Suppress(
        "ReturnCount",
        "LongMethod", // Spec §1.5's trafficLoop.start() call pushed this one line past the threshold.
    )
    private suspend fun attachTun(
        gen: Int,
        xray: XrayController,
        ports: StartedPorts,
        dnsPlan: DnsPlan?,
        rowId: Long,
    ): TunAttachOutcome {
        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.EstablishingTun))) {
            return TunAttachOutcome.Settled
        }
        val plan =
            when (val gate = resolvePerApp(gen, xray, rowId)) {
                is PerAppGateResult.Proceed -> gate.plan
                PerAppGateResult.Failed -> return TunAttachOutcome.Settled
                is PerAppGateResult.NoPlan -> return TunAttachOutcome.NoPerAppPlan(gate.detail)
            }
        // Spec §5.2, read *before* establish(): sampling after it means asking
        // the framework which network is default at the moment we are adding one,
        // and this app's own package is excluded from the TUN on every
        // BuilderPlan (§8) — so the answer is a physical network either way, but
        // only this ordering makes that true without depending on the exclusion.
        //
        // The window is not free. If NetworkMonitor's onChanged fires between
        // this read and the setUnderlyingNetworks call below, this stale sample
        // overwrites the fresher one. That is bounded and self-correcting rather
        // than harmless: the value only feeds metered-ness and transport
        // reporting, and the next transition re-sets it. It is not claimed to be
        // impossible — an earlier draft of this comment said the fresher value
        // could not be lost, which was wrong.
        val underlying = activeNetwork()
        val fd =
            when (val established = establishOrFail(gen, xray, plan, dnsPlan, rowId)) {
                is EstablishOutcome.Established -> established.fd
                EstablishOutcome.Settled -> return TunAttachOutcome.Settled
                is EstablishOutcome.NoPerAppPlan -> return TunAttachOutcome.NoPerAppPlan(established.detail)
            }
        synchronized(lock) {
            if (gen != generation) {
                // Superseded while establishing. Close what we just made rather
                // than letting teardown miss it — it never saw this fd.
                fd.close()
                return TunAttachOutcome.Settled
            }
            // §5.4/§6.1: a fail-closed retry reaches this line with the *previous*
            // attempt's fd still in [tunInterface] — [settleRetryableFailure]
            // deliberately left it open as the kill switch, and nothing between
            // there and here closes it. Assigning over it would leak an fd per
            // retry, unboundedly, which is the wedged-until-reboot outcome §5.4
            // names. Closing first is not belt-and-braces: it is the only close
            // that path ever gets.
            closeRetainedTunLocked()
            tunInterface = fd
            // Recorded with the fd, not derived later: [establishTun] built this
            // interface from [dnsPlan], and once it exists nothing can change
            // what it advertises without rebuilding it.
            tunAdvertisedDns = advertisedTunDnsAddress(dnsPlan?.tunAdvertisedAddress(), DNS_SERVER)
        }
        // Spec §5.2, the case NetworkMonitor's callback cannot cover: a session
        // that starts and never changes network never sees `onChanged`, because
        // NetworkTransitionDebouncer.prime() deliberately suppresses
        // registerDefaultNetworkCallback's replay of the already-current
        // network. That is the common path, and until now it left the VPN with
        // no underlying network declared at all — so metered-ness and transport
        // reported to every app querying through the tunnel (including this
        // app's own geoRefreshOnMetered and pingOnLaunchMetered) were whatever
        // the platform inferred. Set here rather than by undoing prime(): the
        // spurious start-time NetworkChanged reconcile that prime() exists to
        // prevent would tear down and rebuild a tunnel that was never
        // interrupted. Outside the lock — this is a binder round trip and
        // nothing below reads it.
        underlying?.let { setUnderlyingNetworks(arrayOf(it)) }

        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.StartingTunnel))) {
            return TunAttachOutcome.Settled
        }
        val config = tun2socksConfig(socksPort = ports.socksPort, mtu = TUN_MTU)
        // The shim refuses a bad fd rather than aborting the process; false here
        // is a real start failure, never something to ignore.
        if (!Tun2Socks.start(config, fd.fd)) {
            xray.stop()
            // [fd] is deliberately left attached as [tunInterface] and is not
            // closed here — the same reasoning [attachRetainedTun]'s own
            // `Tun2Socks.start` failure block carries, now that the two agree.
            //
            // §6.1: `TunnelStartFailed` is `Retryable`, so this reaches
            // [settleRetryableFailure], which asks [shouldRetainTun] — true with
            // the kill switch on, which is the default. Closing and nulling here
            // left that decision with nothing to retain: the TUN came down,
            // `0.0.0.0/0` and `::/0` went with it, and traffic ran in the clear
            // while the user believed fail-closed was holding it.
            //
            // §5.4's exactly-once close still holds on every path out of here.
            // If [gen] is current: [settleTerminalFailure]'s `lifecycle` calls
            // [closeRetainedTunLocked] unconditionally, and
            // [settleRetryableFailure]'s calls it whenever [shouldRetainTun] is
            // false — so both non-retaining outcomes close it once, under [lock].
            // When it *is* retained it stays in [tunInterface], and is closed
            // once, later, by whichever of these comes first: the next
            // [attachTun]'s [closeRetainedTunLocked] before it adopts its own
            // fd; a later settlement that does not retain — any terminal one,
            // or a retryable one once fail-closed is off or intent is cleared;
            // or [stopTunnel].
            //
            // If [gen] is already stale, [stopTunnel] superseded it and has
            // taken [tunInterface] and closed it. Nothing else can supersede an
            // attempt that has not yet published `Connected`: a new
            // [startTunnel] folds into it rather than bumping the generation,
            // and [restartCoreRetainingTun] runs only after a reconcile read
            // `Connected`. Closing from here as well would be a second,
            // unsynchronised close of an fd this generation no longer owns,
            // which is the bug §5.4 warns about rather than a safety margin.
            failStart(
                gen,
                FailureReason.TunnelStartFailed,
                IllegalStateException("tun2socks refused to start"),
                rowId,
            )
            return TunAttachOutcome.Settled
        }

        val connected = ConnectionState.Connected(System.currentTimeMillis(), ports.socksPort, ports.httpPort)
        // One generation-checked transition: the connected notification is established and
        // `Connected` published together under the lock, and the spec-D4 success write (see
        // ConnectionRecorder) happens strictly after. Previously this published, suspended in
        // the write, and called goForeground() on the way out — so a teardown during the
        // write left this coroutine to restore the connected notification over a tunnel that
        // was already down (§5.5). Nothing may be added after the `persist` lambda.
        terminalOutcome.settleHandlingLifecycleRejection(
            gen = gen,
            state = connected,
            lifecycle = {
                // Spec §2.4: a successful connect means whatever retry was pending for the
                // failure this replaces no longer applies. [retireRetryIfEstablished] runs
                // here, inside the generation-checked transition under [lock], and not in
                // `persist` — `persist` runs after `record()` suspends, and a newer generation
                // can arm its own retry during that suspension. Committed and still-current
                // are different properties: cancelling from `persist` cancelled whichever
                // generation's timer happened to be live when the write finally resumed,
                // which is not necessarily this one's. From here a superseded generation
                // cannot reach this line at all.
                //
                // Fix round 1, Finding 1: the counter reset (bundled into `retireRetry` below)
                // means the next outage starts from zero, not from wherever this one left off.
                // Gated on `established`: a rejected foreground hands off to
                // [handleForegroundLifecycleRejection], which owns whether the retry dies with
                // the session it is about to stop — not this call.
                retireRetryIfEstablished(
                    established = goForeground(R.string.notification_state_connected),
                    retireRetry = {
                        // `Locked`, not the `synchronized` wrapper: [lock] is already held by
                        // [TerminalOutcome.settle].
                        cancelBackoffRetryLocked()
                        reconnectAttempts.reset()
                        // Spec §1.5: gated on `established` like the two calls above —
                        // a rejected foreground never really connected.
                        trafficLoop.start()
                    },
                )
            },
            persist = { connectionRecorder.record(rowId, connected) },
            onLifecycleRejected = { handleForegroundLifecycleRejection(gen, rowId) },
        )
        // The settlement's own outcome needs no branch here: all three leave this
        // attempt with nothing further owed. `Committed` published `Connected`;
        // `Superseded` means a newer generation owns the tunnel and this one must
        // not touch it; `LifecycleRejected` has already been settled by
        // [handleForegroundLifecycleRejection]. That is exactly what
        // [TunAttachOutcome.Settled] says — and the `if` that stood here returned
        // it from both branches, reading as though the call site could tell the
        // committed case from the other two.
        return TunAttachOutcome.Settled
    }

    /**
     * Publishes a specific outcome and cleans up what a failed start leaves.
     *
     * Fix round 1, Finding 1: a retryable [reason] does not settle as
     * terminal `Failed` — it settles as `Reconnecting` and schedules the
     * backoff. Spec §6.1 is explicit that reconnection happens regardless of
     * the fail-closed setting ("the setting decides only whether traffic runs
     * in the clear while it does"), so this decision reads only
     * [FailureReason.retryability]; it never reads `SettingsRepository.failClosed`
     * or anything else that would make retrying conditional on that setting.
     * TUN retention is a separate decision — [shouldRetainTun] — consulted only
     * inside [settleRetryableFailure].
     *
     * §5.6: the config file holds the UUID and REALITY key. A failed start used
     * to leave it on disk indefinitely, because only teardown deleted it.
     */
    private suspend fun failStart(
        gen: Int,
        reason: FailureReason,
        cause: Exception,
        rowId: Long,
    ): Nothing? {
        // failure() redacts at construction — libXray's errors quote the config
        // straight back (§5.6). Built before either branch because that is the
        // one thing here with no lifecycle effect, and both branches need it:
        // the terminal one to publish, the retryable one only if it turns out
        // to be at the cap after all.
        val failed = failure(reason, cause.message.orEmpty())
        when (val retryability = reason.retryability()) {
            Retryability.Terminal -> settleTerminalFailure(gen, failed, rowId)
            Retryability.Retryable, Retryability.RetryableCapped ->
                settleRetryableFailure(gen, reason, failed, rowId, retryability)
        }
        return null
    }

    /**
     * §1.2/§2.2: publishes `Failed`, clears session intent, and releases the
     * started-service lifetime — the outcome only the user can act on.
     *
     * `suspend`, not plain: the one write `:bg` performs on failure (spec D4) runs
     * here, and it runs *after* the whole transition rather than in the middle of it.
     * `stopForeground()`/`stopSelf()` used to follow that write, so a newer connection
     * starting during it inherited this failure's teardown — its foreground state removed,
     * or its service stopped, by the previous attempt (PR #4 review, P1 finding A). Both are
     * now inside the generation-checked transition; nothing may be added after it.
     *
     * The intent clear inside `persist` is conditional on this settlement still
     * owning the session — see [SessionIntentGate] for the window that makes an
     * unconditional clear, or a generation re-check, wrong.
     */
    private suspend fun settleTerminalFailure(
        gen: Int,
        failed: ConnectionState.Failed,
        rowId: Long,
    ) {
        // Assigned inside `lifecycle`, which runs under [lock] and only after
        // `settle` has confirmed [gen] is still current — so the value read is
        // the token belonging to the session this settlement is committing for,
        // not whatever the gate has moved on to by the time `persist` runs.
        var ownedIntentToken = 0
        terminalOutcome.settle(
            gen = gen,
            state = failed,
            lifecycle = {
                ownedIntentToken = sessionIntentToken
                configFile?.delete()
                configFile = null
                controller = null
                liveSession = null
                reconnectAttempts.reset()
                // A terminal outcome ends the retry sequence, so the timer that
                // sequence armed must not outlive it — see [cancelBackoffRetry].
                // A `RetryableCapped` failure at the cap reaches here from
                // [settleRetryableFailure] with a job already scheduled by the
                // attempt before it, and without this that job fires
                // BackoffElapsed into the `Failed` this is publishing. Inline
                // rather than cancelBackoffRetry(): this lambda runs under
                // [lock] already.
                cancelBackoffRetryLocked()
                closeRetainedTunLocked()
                val startId = activeStartId
                activeStartId = 0
                removeForegroundSafely()
                stopStartedService(startId)
                true
            },
            persist = {
                // NonCancellable for the same reason onRevoke needs it, and it
                // is the same race: `lifecycle` above has already called
                // stopStartedService, which can destroy the service, and
                // onDestroy calls scope.cancel() — so these two writes run in a
                // window where the scope they belong to may already be gone.
                // Not observed losing, and cheap to make independent of whether
                // it would.
                withContext(NonCancellable) {
                    connectionRecorder.record(rowId, failed)
                    // Spec §1.2/§2.2: one of the intent-clearing sites. Inside
                    // `persist` rather than beside `settle` so a superseded
                    // generation (this attempt lost the race) never clears intent
                    // for a session that is not this one's to clear.
                    //
                    // Being inside `persist` is not sufficient on its own, and a
                    // generation re-check here would not help either. The record
                    // above suspends on a Room write, and the command coordinator
                    // — a different coroutine — can accept a new connect during
                    // it. That connect writes `wanted = true` *before* calling
                    // startTunnel, so there is a window where the new session's
                    // intent exists and the generation has not moved yet. The
                    // gate's token moves with the write instead, and holds its
                    // own mutex across both the check and the clear, so a
                    // connect's write cannot land between them either.
                    sessionIntent.clearIfOwned(ownedIntentToken)
                }
            },
        )
    }

    /**
     * §2.2/§2.4: holds session intent, publishes `Reconnecting`, and schedules
     * the backoff — a retryable failure is not a reason to stop, and this is
     * unconditional: nothing here makes *reconnection* conditional on the
     * fail-closed setting (fix round 1, Finding 1 — reconnection happens
     * whether or not it is on). Only TUN retention depends on it, decided by
     * [shouldRetainTun] and applied nowhere else in this function's control
     * flow — R17: gating `Reconnecting`/the backoff on that setting would
     * silently delete reconnection for anyone who turns fail-closed off.
     *
     * Reuses [terminalOutcome]'s atomic gen-check-then-mutate-then-publish
     * transition — the same primitive [publishIfCurrent] is built on — rather
     * than a second hand-rolled one: a superseded generation must not null out
     * a *newer* generation's `controller`/`configFile` any more than it must
     * publish over a newer generation's state, and `TerminalOutcome` already
     * closes exactly that race. Used here for a non-terminal state
     * deliberately — the mechanism is state-shape-agnostic even though its
     * name is not.
     *
     * Unlike [settleTerminalFailure], this does **not** clear intent, remove
     * the foreground notification, or resolve the started-service lifetime:
     * the session is still wanted, still trying, and the service must survive
     * to run the scheduled retry — see [startTunnel]'s guard clause, which
     * fix round 1, Finding 1 also had to open up to a `Reconnecting`
     * `currentState`, or the retry this schedules would be silently ignored
     * the moment it fires.
     *
     * §2.3: a `RetryableCapped` reason whose next attempt would reach
     * [space.getsub.core.model.TUN_ESTABLISH_ATTEMPT_CAP] settles as terminal
     * instead (fix round 1, Finding 2) — see [nextAttemptExceedsCap].
     *
     * Fix round 2: [trialAttempt] is a *peek*, not a commit. `failStart` is
     * reachable with a [gen] that is already stale, and not from a few named
     * sites but from nearly every one. Most of [startCore]'s failures return
     * `failStart` with no generation check ahead of them (`allocatePorts` has
     * none at all), [resolveAndStartCore]'s catch block is another, and
     * [attachTun]/[attachRetainedTun] call it from their `Tun2Socks.start`
     * failure blocks deliberately without one — the fd those paths leave
     * attached belongs to whoever holds the generation, and this settlement is
     * where that is resolved. So this function cannot assume [gen] is current
     * on entry. [ReconnectAttemptCounter.commit]
     * therefore runs inside `lifecycle`, alongside `controller`/`configFile`/
     * `liveSession`, so a superseded [gen] leaves the counter exactly as it
     * found it: [ReconnectAttemptCounter.peekNext] has no side effect, and
     * `terminalOutcome.settle` never runs `lifecycle` — hence never calls
     * `commit` — for a generation that lost the race.
     */
    private suspend fun settleRetryableFailure(
        gen: Int,
        reason: FailureReason,
        failed: ConnectionState.Failed,
        rowId: Long,
        retryability: Retryability,
    ) {
        val trialAttempt = synchronized(lock) { reconnectAttempts.peekNext() }
        if (nextAttemptExceedsCap(retryability, trialAttempt)) {
            settleTerminalFailure(gen, failed, rowId)
            return
        }
        // Read before `terminalOutcome.settle`, not inside its `lifecycle`
        // lambda: that lambda runs under `lock` and must not suspend, and both
        // reads below are suspend. `tunnelSessionWantedNow()` — rather than
        // assuming true because nothing in this function clears intent — is
        // the same one-shot read `reconcileNow` uses, so this does not rely on
        // every intent-clearing site continuing to pair with a generation bump
        // to stay correct.
        val retainTun =
            shouldRetainTun(
                failClosed = settingsRepository.failClosed.first(),
                intentWanted = settingsRepository.tunnelSessionWantedNow(),
                retryability = retryability,
            )
        terminalOutcome.settle(
            gen = gen,
            state = ConnectionState.Reconnecting(reason, trialAttempt),
            lifecycle = {
                configFile?.delete()
                configFile = null
                controller = null
                liveSession = null
                reconnectAttempts.commit(trialAttempt)
                // Spec §6.1: the kill switch itself. Skipping the close here —
                // rather than adding any route or block — is what leaves a TUN
                // with nothing servicing it as a blackhole for the wanted
                // session's traffic instead of a torn-down interface.
                if (!retainTun) closeRetainedTunLocked()
                // §6.3/§6.4: the notification reports the **observed** TUN, not
                // the fail-closed setting and not [shouldRetainTun]'s decision
                // on its own. Those two are not the same fact, and which side a
                // given [FailureReason] lands on depends on what ran before it,
                // not on the reason.
                //
                // [tunInterface] is read *after* the conditional close above, so
                // it is null whenever `retainTun` is false. When `retainTun` is
                // true it is null only if there was nothing to retain: this
                // attempt failed before adopting an fd — PortAllocationFailed or
                // CoreStartFailed inside [startCore], TunEstablishFailed inside
                // [establishOrFail] — and inherited none, which is the case on a
                // sequence's first attempt and on any retry after a settlement
                // that did not retain. Every packet then leaves in the clear
                // however the setting reads. It is non-null when this attempt
                // attached a TUN and failed afterwards — TunnelStartFailed from
                // [attachTun] or [attachRetainedTun], both of which leave the fd
                // in place for exactly this decision — or when it inherited one a
                // previous retaining settlement held, whatever the reason.
                //
                // That is why this reads the field instead of enumerating
                // reasons. §6.4 defends defaulting fail-closed *on* entirely on
                // the promise that §6.3's notification tells the truth, so
                // reading the retention decision alone made that promise false.
                // Reading `tunInterface` here needs no extra synchronization:
                // this lambda already runs under `lock`, after the
                // `closeRetainedTunLocked()` above has settled what is left.
                //
                // Its own success/failure does not gate this transition: a
                // rejected foreground update here must not silently drop the
                // Reconnecting state the user is waiting on.
                goForeground(
                    if (retainTun && tunInterface != null) {
                        R.string.notification_state_reconnecting_blocked
                    } else {
                        R.string.notification_state_reconnecting_open
                    },
                )
                true
            },
            persist = { scheduleBackoffRetry(trialAttempt) },
        )
    }

    /**
     * Closes and clears [tunInterface] when this generation's start sequence
     * failed instead of committing and left an fd behind — a retained-fd restart
     * ([restartCoreRetainingTun]), a fail-closed retry, or a `Tun2Socks.start`
     * failure that attached its fd before failing (§5.4: the fd must still close
     * on every path where the session ends).
     *
     * Called unconditionally from [settleTerminalFailure] — a terminal outcome
     * always ends the retained TUN's life, fail-closed or not (spec §6.1: no
     * retry means holding it would leave the device with no connectivity and
     * nothing working to restore it). Called *conditionally* from
     * [settleRetryableFailure], guarded by [shouldRetainTun]: when that
     * returns true this is skipped on purpose, and the fd it would have closed
     * is the entire kill switch. Either way, the next `Start` establishes a
     * fresh TUN via [attachTun] rather than reusing this one — which is why
     * [attachTun] is the third caller: it closes whatever a retained-fd
     * retry left behind immediately before adopting the fd it just
     * established, and that is the only close a successful retry ever
     * performs on the retained one.
     *
     * A no-op only for a start sequence that failed *before* adopting an fd and
     * inherited none — [tunInterface] is then already null by the time
     * [failStart] runs. It is not a no-op for a `Tun2Socks.start` failure in
     * either [attachTun] or [attachRetainedTun]: both deliberately leave the fd
     * attached and reach [failStart] with it still in [tunInterface], so this is
     * the close those paths get when the settlement does not retain it.
     *
     * Must be called with [lock] already held — this does not take it itself.
     * That is a `lifecycle` block for the two settle paths, and [attachTun]'s
     * own plain `synchronized(lock)` region for the third; the requirement is
     * the lock, not the lambda.
     */
    private fun closeRetainedTunLocked() {
        try {
            tunInterface?.close()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "closing retained tun fd failed: ${e.javaClass.simpleName}")
        }
        tunInterface = null
        tunAdvertisedDns = null
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

    /**
     * The network the platform currently treats as this app's default, or null
     * when there is none.
     *
     * Read directly from [ConnectivityManager] rather than through
     * [networkMonitor]: the monitor's contract is two callbacks, and widening it
     * with a "what is current" accessor would give a second, separately-aged
     * copy of a fact the framework already answers authoritatively. It also
     * cannot be null-by-lifecycle here the way the monitor can — the monitor is
     * registered on [SettingsRepository.tunnelSessionWanted], which is written
     * from a different coroutine than the start sequence runs on.
     *
     * This app excludes its own package from the TUN on every [BuilderPlan]
     * (§8), so the answer is a physical network even while our own VPN is up.
     * [attachTun] still samples it before `Builder.establish()` — see there.
     */
    private fun activeNetwork(): Network? =
        getSystemService(ConnectivityManager::class.java)?.activeNetwork

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
        // Spec §5.2. Without this a VpnService network is **always** metered:
        // that is the platform default, and it is applied on top of the
        // underlying networks rather than derived from them, so declaring the
        // right underlying network does not undo it. Measured on a Pixel 8
        // (Android 17): with `underlying=[wlan0]` correctly declared and the
        // Wi-Fi network carrying NET_CAPABILITY_NOT_METERED, the tunnel still
        // reported itself metered until this call was added.
        //
        // `false` does not claim the tunnel is unmetered — it declines to force
        // the answer. AOSP's `Vpn.applyUnderlyingCapabilities` ORs this flag
        // with each underlying network's own metered state, so `false` means
        // "inherit from what I am running over", which is exactly what §5.2
        // asks for: apps querying through the tunnel, including this app's own
        // geoRefreshOnMetered and pingOnLaunchMetered, see the real network's
        // cost. On cellular the tunnel still reports metered, correctly.
        //
        // API 29+; minSdk is 26, and on 26-28 the platform simply has no way to
        // express this, so those devices keep the always-metered behaviour.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
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
     * as [space.getsub.core.model.DnsValidation.isAddressLiteral], whose
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

    /**
     * The notification text a framework start should assert, given what this
     * service is currently doing (§6.3).
     *
     * [onStartCommand] must answer `startForegroundService`'s contract on a start
     * that carries no connect request, and [TunnelNotification.ID] is a single
     * shared id — so that call *replaces* the live session's text rather than
     * adding to it. Returning the current state's own text is what stops an
     * unrelated always-on or boot start falsifying it.
     *
     * `Reconnecting` reproduces [settleRetryableFailure]'s rule rather than
     * restating the setting: it reports the **observed** TUN, because
     * `shouldRetainTun` returning true and an fd actually being held are not the
     * same fact — an attempt that failed before adopting one retains nothing
     * however the setting reads. [tunInterface] is sampled in the same lock region
     * as [currentState], so the pair cannot disagree.
     *
     * `Failed` gets the connecting text, which is momentarily untrue: it is the
     * one state where the reconcile that follows answers
     * [ReconcileAction.Release] and takes the notification straight back down.
     * Publishing a truthful text for it would mean a "failed" string that exists
     * only to be removed milliseconds later.
     *
     * Exhaustive with no `else`, for spec §2.2's reason: a state added later must
     * not inherit whichever text happens to catch it.
     */
    private fun entryForegroundText(): Int =
        synchronized(lock) {
            when (currentState) {
                is ConnectionState.Connected -> R.string.notification_state_connected

                is ConnectionState.Reconnecting ->
                    if (tunInterface != null) {
                        R.string.notification_state_reconnecting_blocked
                    } else {
                        R.string.notification_state_reconnecting_open
                    }

                is ConnectionState.Connecting,
                ConnectionState.Disconnecting,
                ConnectionState.Disconnected,
                is ConnectionState.Failed,
                -> R.string.notification_connecting
            }
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
     * What [stopTunnel] took from the session it ended, in its one lock region.
     *
     * @property startId the started-service token that session held.
     * @property intentToken [sessionIntentToken] as it stood in that same region.
     *   For a stop that passed `expectedGeneration`, that is the token of the very
     *   session the stop proved it was ending — so a rejection path clears with
     *   this, never with a value read after [stopTunnel] returns. By then the lock
     *   has been released and the slow teardown has run, and a newer connect may
     *   have moved the gate and had its token recorded.
     */
    private data class StoppedSession(val startId: Int, val intentToken: Int)

    /**
     * Idempotent, and reachable from three unsynchronised places (§5.4).
     *
     * State is taken under [lock] in one shot; the slow work then runs outside it
     * so a wedged `quit()` cannot block publication. A second concurrent call
     * finds every field already null and does nothing twice.
     *
     * @return null when `expectedGeneration` no longer owns the tunnel;
     *   otherwise what the stop took from the session it ended.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun stopTunnel(
        finalState: ConnectionState,
        expectedGeneration: Int? = null,
    ): StoppedSession? {
        // §11 row W7. A teardown that never returns wedges
        // [TunnelCommandCoordinator]'s single consumer: it processes commands in
        // one sequential loop, so the user's next Connect queues behind this call
        // forever while the session sits in `Disconnecting` with the fd still open
        // and §6.1's kill switch still blackholing traffic for a session the user
        // has already ended. Observed on device 2026-09-16.
        //
        // Each step logs on entry and on return, so a hang reads as an `enter`
        // with no matching `exit` instead of being indistinguishable from a fast,
        // silent success — which is what made the first occurrence undiagnosable.
        // §5.6: phase names and durations only, never config contents.
        val startedAtMillis = android.os.SystemClock.elapsedRealtime()
        fun sinceStart(): Long = android.os.SystemClock.elapsedRealtime() - startedAtMillis
        Log.i(TAG, "teardown: enter")

        val xray: XrayController?
        val fd: ParcelFileDescriptor?
        val cfg: File?
        val startId: Int
        val intentToken: Int

        synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != generation) {
                Log.i(TAG, "teardown: superseded, nothing taken +${sinceStart()}ms")
                return null
            }
            // Supersede any in-flight start before taking ownership of its state.
            ++generation
            xray = controller
            fd = tunInterface
            cfg = configFile
            controller = null
            tunInterface = null
            tunAdvertisedDns = null
            configFile = null
            liveSession = null
            // Every stopTunnel caller (explicit disconnect, onRevoke, a
            // deliberate reapplyPerApp/network-change restart) ends whatever
            // retry sequence was in progress; the next one starts at 1, not
            // wherever this one left off.
            reconnectAttempts.reset()
            // …and the pending timer for that sequence dies with it. Resetting
            // the counter without cancelling the job left a retry armed against
            // a session this call is ending: it would later fire BackoffElapsed
            // into a reconcile that can flatten a published Failed(Revoked)
            // into Disconnected (§11 row 7). Inline rather than
            // cancelBackoffRetry(), which takes [lock] this block already holds.
            cancelBackoffRetryLocked()
            startId = activeStartId
            activeStartId = 0
            // Captured here, in the same region as the generation check above, and
            // deliberately not reset: see [StoppedSession.intentToken].
            intentToken = sessionIntentToken
            publishLocked(ConnectionState.Disconnecting)
        }

        Log.i(TAG, "teardown: state taken +${sinceStart()}ms")

        // Order matters: stop feeding packets in before removing their destination.
        Log.i(TAG, "teardown: tun2socks.stop enter")
        try {
            Tun2Socks.stop()
        } catch (e: Throwable) {
            // Includes NoClassDefFoundError when System.loadLibrary failed. §5.4
            // says teardown must still finish — abandoning here leaks the fd.
            Log.e(TAG, "tun2socks stop failed: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "teardown: tun2socks.stop exit +${sinceStart()}ms")

        try {
            fd?.close()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "closing tun fd failed: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "teardown: fd.close exit +${sinceStart()}ms")

        // stopBlocking(), not stop(): onDestroy has no scope that outlives it and
        // §5.4 requires teardown to finish before the process dies. It also drops
        // the protector so Go stops holding this service.
        Log.i(TAG, "teardown: xray.stopBlocking enter (present=${xray != null})")
        xray?.stopBlocking()
        Log.i(TAG, "teardown: xray.stopBlocking exit +${sinceStart()}ms")
        cfg?.delete()

        removeForegroundSafely()
        publish(finalState)
        Log.i(TAG, "teardown: done +${sinceStart()}ms")
        // Spec §1.5 / TeardownStep.StopTrafficSampler: stopped after the session
        // has published its final state and before the capture below stops —
        // a session that has already ended must not go on accumulating a total
        // for it, or emit one to a client that just heard it is over.
        trafficLoop.stop()
        // Spec §3.3: stopped last, after every phase above has logged — a
        // capture that stops first would go quiet before the teardown becomes
        // interesting. Guarded like the native calls above: a diagnostic must
        // not stop this teardown from finishing.
        runCatching { logCapture.stop() }
        return StoppedSession(startId = startId, intentToken = intentToken)
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
     *
     * The intent-clearing write below runs on [NonCancellable] because the race
     * it closes is real, not because it was ever observed losing.
     *
     * `onRevoke()` is not suspend and `runBlocking` is not permitted here, so
     * the write has to be launched rather than awaited. [stopTunnel] below then
     * stops the started service, which destroys it, and [onDestroy] calls
     * `scope.cancel()` — so a write launched as a child of [scope] is racing its
     * own scope's cancellation. On device (§11 row 7) it won that race
     * comfortably: an instrumented run showed `write DONE` before
     * `onDestroy ENTER`. But winning is a property of how fast this particular
     * Room write happens to be, not of the ordering, and losing it leaves
     * `wanted = true` after a revoke — which is exactly what §1.2 exists to
     * prevent, since the next boot or always-on bind would then reconnect and
     * fight for the route the user just handed to another VPN app.
     *
     * [NonCancellable] gives the write its own job instead of a child of
     * [scope], so the outcome no longer depends on that timing at all.
     *
     * The clear is conditional, like every intent write in `:bg`, and that
     * matters *because* it outlives this instance. The token is read here,
     * synchronously, before the launch: a connect accepted after this line
     * moves the gate's token, and the late clear is refused instead of
     * ending the session that connect starts. The gate is one per `:bg`
     * process ([ServiceModule]), so the check still holds when that connect
     * lands on a new `TunnelService` created after this one was destroyed —
     * the exact shape a per-instance gate missed (see [SessionIntentGate]).
     */
    override fun onRevoke() {
        // Spec §1.2: the second of three intent-clearing sites. Reconnecting into
        // a route another VPN app just took, or that the user just revoked, is a
        // fight this app should lose, loudly and immediately — not retry into.
        val revokedToken = sessionIntent.currentToken()
        scope.launch(NonCancellable) { sessionIntent.clearIfOwned(revokedToken) }
        stopTunnel(failure(FailureReason.Revoked, "VPN permission revoked"))
            ?.let { stopped -> stopStartedService(stopped.startId) }
    }

    override fun onDestroy() {
        // Preserve a terminal failure (notably Revoked) rather than flattening it.
        val finalState =
            synchronized(lock) {
                currentState as? ConnectionState.Failed ?: ConnectionState.Disconnected
            }
        cancelBackoffRetry()
        // Cancelled before networkMonitor.stop(), not left to scope.cancel() at the
        // end: the collector's `if (wanted) start() else stop()` body has no
        // suspension point, so a `true` emission landing after an explicit stop()
        // but before scope.cancel() completes would call start() again and
        // re-register a callback that nothing is left to unregister. Cancelling
        // this job first means no *further* emission can reach that body at all.
        // It does not preempt an invocation already mid-flight at the instant this
        // runs — onDestroy() is not suspend, so there is no cancelAndJoin() to
        // reach for here — but that residual window is far narrower than the
        // ordering this replaces, which left it open until scope.cancel() at the
        // very end of this function.
        networkMonitorJob?.cancel()
        networkMonitor.stop()
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
        } else if (balancerLatencyRefusal(profile.rawJson) != null) {
            // Checked before either mode, for the same reason the foreign-VPN guard is:
            // both modes would otherwise return a number that describes one member.
            LatencyResult.failed(LatencyOutcome.UNSUPPORTED)
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
        // Spec §1.2: the first of three intent-writing sites. True the moment the
        // command is *accepted* — not when it succeeds — so a boot-time or
        // always-on connect that dies at StartingCore is still wanted and still
        // retried; that ordering is the whole reason this milestone exists.
        //
        // Through [sessionIntent] rather than the repository directly: this write
        // is what mints the session's ownership token, and the token has to move
        // inside the same critical section as the write, or a settlement running
        // concurrently could clear the intent this line just established. That it
        // happens *before* startTunnel — and so before the generation bump — is
        // exactly why the generation counter cannot guard that clear.
        //
        // The token comes back from `want()` itself, taken inside that critical
        // section, and is handed to whichever path this connect takes — so the
        // session it starts owns exactly the intent this line wrote.
        val intentToken = sessionIntent.want()
        val decoded = profile.toProfile()
        if (decoded == null) {
            rejectConnectFromCommand(startId, profile.rowId, intentToken)
            return
        }
        startTunnel(decoded, profile.rowId, startId, intentToken)
    }

    // ── Reconciliation ──────────────────────────────────────────────────────

    /**
     * Spec §3.1/§3.3: re-decides what should be running, from the reconcile
     * channel's single ordering point (Task 5's pure [reconcile]).
     *
     * Reads [currentConnectionState] — the same holder [publishIfCurrent]
     * writes — and [commandIngress]'s framework start token, never a second
     * copy of either; see their own KDoc for why a duplicate would reintroduce
     * bugs those mechanisms already close.
     */
    private suspend fun reconcileNow(trigger: ReconcileTrigger) {
        // Spec §2.4: no network means no timer, unconditionally. A retry left
        // over from just before the network dropped must not fire into a
        // decision nobody is making until the network returns.
        if (trigger == ReconcileTrigger.NetworkLost) cancelBackoffRetry()
        // The gate's token for the intent this reconcile is about to read and act
        // on, taken before that read. Only the two "row is not connectable"
        // branches below use it, to clear intent through the gate. This runs on
        // the command coordinator, which is where `want()`'s only caller runs, so
        // the token cannot move while this reconcile is deciding.
        val intentToken = sessionIntent.currentToken()
        val intent =
            SessionIntent(
                wanted = settingsRepository.tunnelSessionWantedNow(),
                profileRowId = settingsRepository.activeProfileIdNow(),
            )
        when (val action = reconcile(intent, currentConnectionState(), trigger)) {
            is ReconcileAction.Start -> startFromRow(action.profileRowId, intentToken)
            is ReconcileAction.Restart -> restartCoreRetainingTun(action.profileRowId, intentToken)
            ReconcileAction.Stop -> stopTunnelAndService()
            ReconcileAction.Release -> releaseServiceWithoutPublishing()
            ReconcileAction.Nothing -> adoptFrameworkStartId()
        }
    }

    /**
     * [ReconcileAction.Nothing]'s effect, which is not nothing.
     *
     * A framework start that lands on a live session is answered `Nothing` —
     * correctly: the session is up and wanted, and releasing it would strip its
     * notification (W2). But [TunnelCommandIngress.reconcile] has already recorded
     * that start as the latest framework token, while the session's eventual
     * settlement stops with [activeStartId]. See [adoptedStartId] for what the
     * divergence costs; this is where it is closed, by moving the live session
     * onto the newer token rather than by resolving it here. Resolving it would
     * be `stopSelfResult` on the newest lifetime of a session that is alive and
     * wanted.
     *
     * Unconditional rather than restricted to [ReconcileTrigger.NullIntentStart]:
     * a framework start can arrive after a `NetworkChanged` reconcile is enqueued
     * and before it is dequeued, so the trigger does not identify which reconciles
     * have a newer token to adopt. [adoptedStartId] answers that from the tokens
     * themselves, and is a no-op whenever there is nothing newer.
     *
     * [liveSession] follows [activeStartId] for the reason [startTunnel]'s fold
     * branch moves both together: `reapplyPerAppFromCommand` restarts from
     * [LiveSession.startId], so leaving it behind would make a per-app rebuild
     * resume under a token the settlement no longer names. It is null while a
     * session is `Reconnecting` — [settleRetryableFailure] clears it and
     * deliberately keeps [activeStartId] — which is exactly a case this must
     * still adopt for, so the two are moved independently rather than gated on
     * each other.
     */
    private fun adoptFrameworkStartId() {
        val frameworkStartId = commandIngress.latestStartId()
        synchronized(lock) {
            val adopted = adoptedStartId(sessionStartId = activeStartId, frameworkStartId = frameworkStartId)
            if (adopted != activeStartId) {
                activeStartId = adopted
                liveSession = liveSession?.copy(startId = adopted)
            }
        }
    }

    /**
     * [ReconcileAction.Release]'s effect: give back the foreground notification
     * and the started-service lifetime, and **publish nothing**.
     *
     * The deliberate absence of a `publish` call is the whole point — see
     * [ReconcileAction.Release]. `currentState` keeps whatever terminal `Failed`
     * it holds, so the reason survives for a `:main` that binds and reads it.
     *
     * Both calls are idempotent, which is what makes this safe on the arms where
     * the terminal settlement has already run them: `stopForeground` on a service
     * that is not in the foreground does nothing, and `stopSelfResult` for an
     * already-resolved token simply reports it. Uses [commandIngress]'s framework
     * token rather than [activeStartId] for the same reason [stopTunnelAndService]
     * does — the session's own id is zero once a settlement has cleared it, and
     * the token that needs resolving is the one `onStartCommand` just received.
     *
     * **That token can belong to a connect which has not run yet, and it is
     * reachable.** [TunnelCommandIngress.started] records `latestStartId` and
     * enqueues the `Connect` under one lock, but intent is written when the
     * coordinator *dequeues* it — so a `Reconcile` already ahead of that connect
     * in the channel reads the pre-connect intent (`wanted = false`, after a
     * terminal settlement), answers `Release`, and resolves the queued connect's
     * token. The `Nothing` this arm answered before `46ff3ff` could not.
     *
     * The cost is bounded by who issues connects. A user-initiated one comes from
     * `:main` through [TunnelClient.connect], and the UI that taps it is bound
     * ([TunnelClient.bind]); a bound service survives `stopSelfResult`, so the
     * queued `Connect` still runs and starts its own lifetime. With nothing bound
     * it would be worse — the service could be destroyed with the `Connect` still
     * in the channel, so the tap would do nothing and `startForegroundService`'s
     * contract would go unanswered — and no such caller exists today: the only
     * unbound starts are the framework's own, which carry no `ACTION_CONNECT`.
     * Recorded rather than guarded, because a guard here would have to know what
     * is queued behind it, and the channel does not expose that.
     *
     * **This refuses while a TUN is attached, and that guard is load-bearing.**
     * Both settlement paths ([settleTerminalFailure], which
     * [settleRetryableFailure] also routes through at the cap) stop the core,
     * close the TUN and cancel the backoff before they publish, so on those arms
     * the check simply passes. [errorHandler] does not: it publishes a terminal
     * `Failed` over a session it deliberately leaves running — see its KDoc,
     * `CoreStartFailed` is `Retryable` and tearing down there released §6.1's
     * kill switch. Without this guard, a framework start arriving after such a
     * crash would take the ongoing notification off a `VpnService` that is still
     * carrying traffic, which §9 requires for the life of the tunnel.
     *
     * It is a property check rather than a claim about callers, and that is the
     * point. An earlier version of this KDoc asserted the teardown *had* already
     * happened; it was false on exactly the [errorHandler] route, and the next
     * publisher of a `Failed` that skips teardown would have falsified it again.
     * Checking the fd cannot go stale that way.
     */
    private fun releaseServiceWithoutPublishing() {
        if (synchronized(lock) { tunInterface } != null) return
        removeForegroundSafely()
        stopStartedService(commandIngress.latestStartId())
    }

    /** [reconcileNow] reads the same state holder [publishIfCurrent] writes — no second copy. */
    private fun currentConnectionState(): ConnectionState = synchronized(lock) { currentState }

    /**
     * [ReconcileAction.Start]'s effect: load the row fresh — never a profile a
     * previous attempt held onto — and connect through the same accepted-command
     * entry point a user-initiated connect uses, so the intent write and
     * generation handling stay in the one place [connectFromCommand] already is.
     *
     * @param intentToken the gate token [reconcileNow] took for the intent it
     *   read — used only to clear that intent when the row cannot be connected.
     */
    private suspend fun startFromRow(
        rowId: Long,
        intentToken: Int,
    ) {
        val profile = profileRepository.profile(rowId)?.toProfile()
        if (profile == null) {
            // §5.6: no name, no address. The row is gone or its config will not
            // decode; either way there is nothing to connect to and holding the
            // service open helps nobody.
            Log.w(TAG, "reconcile: active profile row is not connectable")
            sessionIntent.clearIfOwned(intentToken)
            stopTunnelAndService()
            return
        }
        connectFromCommand(ProfileParcel.from(profile, rowId), startId = commandIngress.latestStartId())
    }

    /**
     * [ReconcileAction.Stop]'s effect. Uses [commandIngress]'s framework start
     * token rather than [stopTunnel]'s own return — that reflects the *session's*
     * start id ([activeStartId], zero when nothing was ever started), and a
     * reconcile deciding to stop must still resolve the started-service lifetime
     * `onStartCommand` actually received, or the service lingers with
     * `START_STICKY` and nothing to do.
     */
    private fun stopTunnelAndService() {
        stopTunnel(ConnectionState.Disconnected)
        stopStartedService(commandIngress.latestStartId())
    }

    /**
     * [ReconcileAction.Restart]'s effect.
     *
     * Spec §5.3/§5.4. The conservative half of the choice.
     *
     * The soft alternative is `setUnderlyingNetworks` alone, letting the core
     * notice its connections died and redial — new dials are protected
     * automatically, because libXray's protector is a dial-time callback
     * (§14.2). There is **no re-protect**: existing sockets cannot be
     * re-marked, so §9's "at minimum a re-protect" describes an operation that
     * does not exist.
     *
     * Which suffices is an empirical question §9 says to answer by physically
     * toggling Wi-Fi, repeatedly — not by unit test and not by reasoning here.
     * Task 16 row 1 settles it.
     *
     * It restarts onto the *active* profile ([rowId], from [reconcileNow]'s
     * read), not necessarily the one the session started on. Spec §1.1 defines
     * session intent as `(activeProfileId, wanted)`, so a server picked while
     * connected is where the next reconcile restart goes — by design (ruling
     * R37), and not to be pinned to the live session's profile. [liveSession]
     * is moved onto it in the handoff below, so the service keeps one answer to
     * "what is connected".
     *
     * The TUN fd genuinely survives this: it is captured from [tunInterface]
     * before anything else runs, and neither this function nor
     * [resolveAndStartCore]/[attachRetainedTun] ever calls `Builder.establish()`
     * or closes it on the success path. Only the old core and the old
     * tun2socks restart — a fresh [XrayController], freshly allocated ports
     * (the old ones die with the old core), and tun2socks repointed at
     * whichever port the new core picked. A failure partway through settles
     * through [settleTerminalFailure]/[settleRetryableFailure], the same as
     * any other failed start: a terminal outcome always closes it via
     * [closeRetainedTunLocked]; a retryable one closes it unless
     * [shouldRetainTun] says to hold it for the kill switch (spec §6.1). Either
     * way a `Start` that follows — whether immediately or after the session
     * reconnects from scratch through [ReconcileAction.Start] — establishes
     * its own TUN via [attachTun] rather than reusing this one.
     */
    // ReturnCount: the missing-profile refusal, the no-retained-fd degrade, and the
    // superseded-generation bailout after resolveAndStartCore are three distinct outcomes,
    // same reasoning as startCore's own early returns.
    // TooGenericExceptionCaught: Tun2Socks.stop()'s Throwable catch mirrors stopTunnel's own —
    // it must include NoClassDefFoundError from a failed System.loadLibrary, not just Exception.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun restartCoreRetainingTun(
        rowId: Long,
        intentToken: Int,
    ) {
        val profile = profileRepository.profile(rowId)?.toProfile()
        if (profile == null) {
            // §5.6: no name, no address — same reasoning as startFromRow's own
            // refusal. Nothing to restart onto, and the retained fd is still
            // live, so route through the ordinary teardown that closes it
            // rather than leaving it dangling. The intent clear goes through
            // the gate with [reconcileNow]'s token, as startFromRow's does.
            Log.w(TAG, "reconcile: active profile row is not connectable")
            sessionIntent.clearIfOwned(intentToken)
            stopTunnelAndService()
            return
        }

        val handoff =
            synchronized(lock) {
                val fd = tunInterface
                if (fd == null) {
                    // Invariant says this should not happen — `reconcile` only
                    // returns `Restart` from a `Connected` actual state, and
                    // `Connected` is never published without a TUN attached.
                    // Degrading to a full reconnect rather than trusting that
                    // invariant blindly is what keeps a violated one from
                    // silently dropping traffic instead of just retrying.
                    null
                } else {
                    val nextGeneration = ++generation
                    val oldXray = controller
                    controller = null
                    configFile?.delete()
                    configFile = null
                    // Spec §1.1: [rowId] is the *active* profile, which can differ
                    // from the row this session started on if another server was
                    // picked while connected (ruling R37: by design). [liveSession]
                    // moves with it, in this same region as the generation bump —
                    // otherwise the core runs the new row while [liveSession] still
                    // names the old one, and [reapplyPerAppFromCommand], which
                    // rebuilds from [liveSession], silently reconnects to the old
                    // row. [LiveSession.startId] is kept: this is the same
                    // started-service lifetime. [sessionIntentToken] is not touched
                    // either — a restart changes what runs, not who owns the intent.
                    // Non-null here: `Restart` only follows a published `Connected`,
                    // and only a settlement or [stopTunnel] nulls it, neither of which
                    // leaves `Connected` behind.
                    liveSession = liveSession?.copy(profile = profile, rowId = rowId)
                    publishLocked(ConnectionState.Connecting(StartupStage.AllocatingPort))
                    RetainedRestart(nextGeneration, oldXray, fd)
                }
            }
        if (handoff == null) {
            startFromRow(rowId, intentToken)
            return
        }

        // Same order stopTunnel uses, and for the same reason: stop feeding
        // packets into the old core before tearing it down. The TUN itself is
        // untouched — [handoff.fd] is not closed here or anywhere on this path.
        try {
            Tun2Socks.stop()
        } catch (e: Throwable) {
            // Includes NoClassDefFoundError, same as stopTunnel's own catch —
            // §5.4 requires this restart to keep going even if the native shim
            // is in a bad state, or the retained TUN outlives a core that never
            // comes back.
            Log.e(TAG, "tun2socks stop failed during restart: ${e.javaClass.simpleName}")
        }
        handoff.oldXray?.stopBlocking()

        val started = resolveAndStartCore(handoff.gen, profile, rowId) ?: return

        // Spec §5.2, lever 1. The core has just been rebuilt from current
        // settings; the retained interface still advertises what it was built
        // with. Keeping it is only safe while those agree.
        //
        // When they do not, the interface is rebuilt through [attachTun] rather
        // than documented as pinned. The case that forces this: the user turns
        // DNS off mid-session, so the new config emits no port-53 hijack, while
        // the TUN goes on advertising the old plan's address — typically the
        // domestic resolver, which a `geoip:<country>` DIRECT rule set then
        // sends out through `freedom`. Every lookup would leave in the clear,
        // proxied sites included. A rebuilt interface advertises DNS_SERVER,
        // which no such rule matches.
        //
        // [attachTun] is the path that already establishes a replacement and
        // retires the old fd under [lock] (`closeRetainedTunLocked`), so this
        // adds a branch, not a new fd lifetime. It also re-resolves the per-app
        // gate, which the retained path deliberately does not: a rebuild
        // therefore applies the current selection.
        //
        // **A rebuild must not end a session the retained path would have
        // survived.** §8's gate can produce no plan at all — allow-list mode with
        // nothing selected, or an allow list whose packages have all been
        // uninstalled — and `PerAppAllowListEmpty` is `Terminal`, so publishing it
        // here would route through [settleTerminalFailure], which closes the
        // retained TUN unconditionally and clears session intent. The user would
        // lose the tunnel *and* §6.1's blackhole to an ordinary Wi-Fi↔cellular
        // change, with nothing on screen explaining why. That is strictly worse
        // than the leak this branch exists to close, which needs DNS off *and* a
        // `geoip:<country>` DIRECT rule to bite.
        //
        // So [attachTun] returns that one case instead of publishing it, and this
        // branch degrades to exactly what the retained path would have done: keep
        // the interface, stale advertised resolver and all. A stale resolver is a
        // real cost; no tunnel and no kill switch is a larger one.
        val nextAdvertisedDns = advertisedTunDnsAddress(started.dnsPlan?.tunAdvertisedAddress(), DNS_SERVER)
        if (retainedTunKeepsAdvertisedDns(synchronized(lock) { tunAdvertisedDns }, nextAdvertisedDns)) {
            attachRetainedTun(handoff.gen, started.xray, started.ports, handoff.fd, rowId)
        } else {
            when (attachTun(handoff.gen, started.xray, started.ports, started.dnsPlan, rowId)) {
                TunAttachOutcome.Settled -> Unit
                is TunAttachOutcome.NoPerAppPlan -> {
                    // §5.6: the reason only. No package names, no counts that
                    // could identify a selection, no addresses.
                    Log.w(TAG, "per-app gate yielded no plan on a rebuild; keeping the existing interface")
                    // [handoff.fd] is still [tunInterface] — [attachTun] returns
                    // this outcome before establishing anything and before
                    // [closeRetainedTunLocked] — so this reattaches the interface
                    // that never went away. §5.4: no fd was opened, so none leaks,
                    // and none is closed twice.
                    attachRetainedTun(handoff.gen, started.xray, started.ports, handoff.fd, rowId)
                }
            }
        }
    }

    /** [restartCoreRetainingTun]'s atomically-captured handoff from the old generation to the new one. */
    private data class RetainedRestart(
        val gen: Int,
        val oldXray: XrayController?,
        val fd: ParcelFileDescriptor,
    )

    /**
     * [restartCoreRetainingTun]'s tail: points a freshly restarted core's
     * tun2socks at the TUN interface that survived the restart, then settles
     * `Connected` exactly like [attachTun]'s own tail — same [TerminalOutcome],
     * same generation gate.
     *
     * Deliberately does not call [establishTun]/`Builder.establish()`, and does
     * not resolve a [PerAppGateResult]: the TUN is not rebuilt, so there is no
     * new fd to race a superseding generation for, and per-app selection is
     * baked into the retained interface — unaffected by the network change
     * that triggered this restart.
     *
     * DNS is **not** in that category, and used to be treated as though it were.
     * The interface advertises the resolver it was built with (§5.2, lever 1)
     * while the core is rebuilt from current settings, so this path is taken
     * only when [restartCoreRetainingTun] has confirmed the two still name the
     * same address — see [retainedTunKeepsAdvertisedDns] for what goes wrong
     * when they do not. Otherwise the caller rebuilds through [attachTun].
     */
    // Each return is a distinct outcome — a superseded generation, a tun2socks restart
    // failure, and a lifecycle-rejected settlement — same reasoning as attachTun's own.
    @Suppress("ReturnCount")
    private suspend fun attachRetainedTun(
        gen: Int,
        xray: XrayController,
        ports: StartedPorts,
        fd: ParcelFileDescriptor,
        rowId: Long,
    ) {
        if (!publishIfCurrent(gen, ConnectionState.Connecting(StartupStage.StartingTunnel))) return
        val config = tun2socksConfig(socksPort = ports.socksPort, mtu = TUN_MTU)
        if (!Tun2Socks.start(config, fd.fd)) {
            xray.stop()
            // [fd] is not closed here. It is still [tunInterface], and whether
            // it closes is the settlement's decision, not this block's:
            // `TunnelStartFailed` is retryable, so [failStart] reaches
            // [settleRetryableFailure], which closes it through
            // [closeRetainedTunLocked] only when [shouldRetainTun] says not to
            // retain — and with fail-closed on, the default, keeps it as §6.1's
            // kill switch. [attachTun]'s own `Tun2Socks.start` failure block
            // carries the same reasoning, including who closes a retained fd
            // later. A close here as well would be a second, unsynchronised
            // close of an fd a concurrent teardown can also reach — the
            // double close §5.4 warns about, not a safety margin.
            failStart(
                gen,
                FailureReason.TunnelStartFailed,
                IllegalStateException("tun2socks refused to restart"),
                rowId,
            )
            return
        }

        val connected = ConnectionState.Connected(System.currentTimeMillis(), ports.socksPort, ports.httpPort)
        val settlement =
            terminalOutcome.settleHandlingLifecycleRejection(
                gen = gen,
                state = connected,
                lifecycle = {
                    // See [attachTun]'s matching site: the cancel and the attempt counter
                    // reset belong to the generation-checked transition, not to `persist` — a
                    // superseded generation must not be able to reach either after `persist`
                    // suspends in `record()`.
                    retireRetryIfEstablished(
                        established = goForeground(R.string.notification_state_connected),
                        retireRetry = {
                            cancelBackoffRetryLocked()
                            reconnectAttempts.reset()
                            // Spec §1.5: [start] is idempotent, so on this retained-TUN
                            // restart it is a no-op — tun2socks was never stopped, so the
                            // sampler already running is left exactly as it was.
                            trafficLoop.start()
                        },
                    )
                },
                persist = { connectionRecorder.record(rowId, connected) },
                onLifecycleRejected = { handleForegroundLifecycleRejection(gen, rowId) },
            )
        if (settlement != TerminalSettlement.Committed) return
    }

    /**
     * Spec §2.4. A plain coroutine delay on the service scope — **not** an alarm and
     * not a `WorkManager` job.
     *
     * Two reasons. The scope dies with the service, so a retry cannot outlive the
     * session it belongs to. And §2.4's rule is that a session with no network
     * schedules nothing at all — a retry timer running in Doze is how §11's
     * six-hour screen-off row fails — which takes enforcement on both edges:
     * `NetworkLost` cancels a job that was already running, and the guard below
     * refuses to arm one when there is no network to retry over in the first
     * place. Either way the service waits on the `NetworkCallback` instead.
     *
     * Called from [settleRetryableFailure]'s `persist` — which confirms the generation
     * *committed*, not that it is still *current* when this runs. `persist` suspends before
     * this call, the same gap the cancel-side P1 fix closed by moving cancellation into the
     * generation-checked `lifecycle` instead of `persist` (see [retireRetryIfEstablished]);
     * this arming call was not moved with it. Neither this function nor its caller re-checks
     * the generation, so a superseded generation can still arm a timer here — the arming-side
     * mirror of that race, reaching the same §11 row 7 corruption named on
     * [cancelBackoffRetry]. Known, pre-existing, and out of scope for that fix.
     */
    private fun scheduleBackoffRetry(attempt: Int) {
        // Read before taking the lock — a binder round trip — and acted on
        // inside it. The value can go stale between the two, which is harmless:
        // a network arriving in that window delivers onAvailable, and one
        // leaving delivers onLost, both of which reach this timer's cancel or
        // re-arm through the ordinary path.
        val hasNetwork = activeNetwork() != null
        // §2.4's rule, on the edge it was previously not enforced on. Cancelling
        // on ReconcileTrigger.NetworkLost only covers a timer that was already
        // running when the network went away; a failure arriving while there is
        // *already* no network — the whole fail-closed-with-no-connectivity
        // case — reached here and armed one anyway. That timer is what §11's
        // six-hour screen-off row fails on. With none armed the service waits on
        // NetworkMonitor's callback instead: it stays registered for as long as
        // the session is wanted (see onCreate), and this failure has just
        // published Reconnecting without clearing intent, so the callback is
        // live. Its onAvailable enqueues Reconcile(NetworkChanged), which
        // `reconcile` answers with Start for a Reconnecting state that may
        // attempt again — the retry resumes from there, not from a clock.
        // Cancel and arm are one atomic step, under one acquisition of [lock].
        // They were previously two — `cancelBackoffRetry()`, then `launch`, then
        // publish the job — and `scope.launch` starts the coroutine immediately,
        // so `backoffJob` was published a line *after* the timer already
        // existed. A cancel landing in that window cancelled whatever the
        // previous attempt had left, and this function then installed a live job
        // over the top of it: armed, unreachable by every cancel site, and
        // guaranteed to fire BackoffElapsed into a session that had just ended.
        // That is exactly the §11 row 7 corruption I1 was raised to close —
        // adding call sites to `cancelBackoffRetry` could not close it, because
        // the job was not yet visible to any of them.
        synchronized(lock) {
            cancelBackoffRetryLocked()
            if (!hasNetwork) {
                Log.i(TAG, "backoff retry not scheduled: no network; waiting on the network callback")
                return
            }
            // `launch` dispatches rather than running inline (the default start
            // mode, not UNDISPATCHED), so the body does not execute under [lock]
            // — only the assignment does.
            backoffJob =
                scope.launch {
                    delay(ReconnectBackoff.delayMillisFor(attempt))
                    commandCoordinator.enqueue(TunnelCommand.Reconcile(ReconcileTrigger.BackoffElapsed))
                }
        }
    }

    /**
     * Cancels a pending retry. Idempotent.
     *
     * Every path on which the session this timer belongs to ends must reach
     * this or [cancelBackoffRetryLocked], because a surviving timer later fires
     * [ReconcileTrigger.BackoffElapsed] into a session that is gone — and a
     * reconcile arriving after a published `Failed(Revoked)` flattens it to
     * `Disconnected`, losing the one fact the user needs (§11 row 7).
     *
     * The call sites, in full:
     *
     *  - [reconcileNow] on [ReconcileTrigger.NetworkLost] — §2.4's no-network,
     *    no-timer rule on the losing edge.
     *  - [onDestroy].
     *  - [stopTunnel], [settleTerminalFailure], and a committed
     *    [ConnectionState.Connected] in both [attachTun] and
     *    [attachRetainedTun] — the failure the pending retry was for is over.
     *    All four already hold [lock] at the point they act — the last two
     *    inside the generation-checked `lifecycle` lambda [TerminalOutcome]
     *    runs — and so call [cancelBackoffRetryLocked] inline instead.
     *
     * [scheduleBackoffRetry] also cancels, first thing, before arming its own replacement —
     * named here rather than counted above, because that one is cancel-then-replace
     * bookkeeping for the *arming* side rather than a session-end cancellation, and it is not
     * generation-checked (see its KDoc).
     */
    private fun cancelBackoffRetry() {
        synchronized(lock) { cancelBackoffRetryLocked() }
    }

    /**
     * [cancelBackoffRetry]'s body for callers that already hold [lock]. Five in this file:
     * [stopTunnel]'s one-shot state capture, [settleTerminalFailure]'s `lifecycle` lambda,
     * [scheduleBackoffRetry]'s own cancel-then-arm, and — since the P1 fix — [attachTun] and
     * [attachRetainedTun]'s committed-`Connected` `lifecycle` lambdas (via
     * [retireRetryIfEstablished]; see [cancelBackoffRetry]'s call-site list for why those two
     * moved here).
     *
     * Split out rather than letting any of them call [cancelBackoffRetry] and rely on the
     * monitor being reentrant: every one of these is a place where "this runs under the lock"
     * is load-bearing, and a helper that quietly re-takes it would invite someone to move a
     * suspending or slow call in beside it.
     */
    private fun cancelBackoffRetryLocked() {
        backoffJob?.cancel()
        backoffJob = null
    }

    private fun reapplyPerAppFromCommand() {
        // Sample only when this command reaches the service-owned consumer.
        // The intent token is sampled with the session, under the same lock: a
        // per-app rebuild changes no intent, so the rebuilt session keeps the
        // owner the live one already had rather than minting or reading one.
        val (session, intentToken) =
            synchronized(lock) {
                liveSession.takeIf { ownTunnelActive() }?.let { it to sessionIntentToken }
            } ?: return
        stopTunnel(ConnectionState.Disconnected)
        startTunnel(session.profile, session.rowId, session.startId, intentToken)
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
        const val LOG_DIR_NAME = "logs"
    }
}

/**
 * Writes the one persistence side effect `:bg` performs (spec D4): the outcome
 * of a connect attempt, against the profile row [ProfileParcel.rowId] names.
 *
 * Takes [recordConnected] and [recordError] as suspend function references —
 * `ProfileRepository::recordConnected`/`recordError` in production — rather
 * than an [space.getsub.core.data.ProfileRepository] directly.
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
