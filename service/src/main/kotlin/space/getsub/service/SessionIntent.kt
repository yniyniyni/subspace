// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState
import space.getsub.core.model.Retryability
import space.getsub.core.model.TUN_ESTABLISH_ATTEMPT_CAP
import space.getsub.core.model.retryability

/**
 * What the user asked for, as opposed to what is currently true.
 *
 * Spec §1: the two halves are persisted separately in `SettingsRepository`
 * (`tunnel_session_wanted` and `active_profile_id`) so they survive `:bg` dying.
 * This type is the pair, read at one instant.
 */
internal data class SessionIntent(
    val wanted: Boolean,
    val profileRowId: Long?,
)

/** Why [reconcile] is being asked. Spec §3.2. */
internal enum class ReconcileTrigger {
    /** Always-on, boot, or a sticky restart: the service came up with no profile in hand. */
    NullIntentStart,
    NetworkChanged,
    NetworkLost,
    BackoffElapsed,
}

/** What [reconcile] decided. Effects belong to `TunnelService`; this type does not perform them. */
internal sealed interface ReconcileAction {
    data class Start(val profileRowId: Long) : ReconcileAction

    /** Spec §5.4: core and tun2socks restart; the TUN fd is retained. */
    data class Restart(val profileRowId: Long) : ReconcileAction

    data object Stop : ReconcileAction

    /**
     * Give back the foreground notification and the started-service lifetime,
     * and **publish nothing**.
     *
     * The answer for a terminal [ConnectionState.Failed], where the other two are
     * both wrong. `Stop` publishes `Disconnected` over the reason the user needs
     * — the erasure [stopUnlessItWouldEraseAFailure] exists to prevent. `Nothing`
     * releases nothing, which strands a framework start's notification and start
     * token on a service that will then never stop (see that function's KDoc for
     * how a start arrives at a settled-down session in the first place).
     *
     * Whoever performs this must not publish: `Failed` surviving it is the
     * property `ReconcileTest` pins, and the only reason this member exists
     * rather than reusing `Stop`.
     */
    data object Release : ReconcileAction

    data object Nothing : ReconcileAction
}

/**
 * Decides the *autonomous* reconcile triggers: start, restart, backoff-elapsed,
 * network change (spec §3.2). Human and external actions — the connect button,
 * an explicit disconnect, `onRevoke` — act directly and merely update the
 * persisted session intent, which the next reconcile then reads; they do not
 * call this function.
 *
 * Pure so it can be tested at all: §11 says the tunnel is verified manually on a
 * device every time, which makes every decision embedded in `TunnelService`
 * effectively untested. This is the decision; the service is the effects.
 */
@Suppress("ReturnCount") // Each return is a distinct spec rule; order is load-bearing.
internal fun reconcile(
    intent: SessionIntent,
    actual: ConnectionState,
    trigger: ReconcileTrigger,
): ReconcileAction {
    // Spec §2.4: no network means no timer and no attempt. Waiting costs nothing;
    // a retry loop in Doze costs the six-hour screen-off case.
    if (trigger == ReconcileTrigger.NetworkLost) return ReconcileAction.Nothing

    if (!intent.wanted) return stopUnlessItWouldEraseAFailure(actual)

    // Wanted, but the row it named is gone. Nothing to start, and holding a
    // foreground service open for a profile that does not exist helps nobody.
    val rowId = intent.profileRowId ?: return stopUnlessItWouldEraseAFailure(actual)

    return when (actual) {
        // A start already in flight must not be started on top of itself; the
        // generation counter would supersede one of them anyway, wastefully.
        is ConnectionState.Connecting, ConnectionState.Disconnecting -> ReconcileAction.Nothing

        ConnectionState.Disconnected -> ReconcileAction.Start(rowId)

        is ConnectionState.Connected ->
            if (trigger == ReconcileTrigger.NetworkChanged) ReconcileAction.Restart(rowId) else ReconcileAction.Nothing

        // [mayAttemptAgain] is always true for a `Reconnecting` this app
        // published — see there. The `Nothing` this can yield is a guard, not a
        // live arm, and is deliberately kept.
        is ConnectionState.Reconnecting ->
            if (actual.mayAttemptAgain()) ReconcileAction.Start(rowId) else ReconcileAction.Nothing

        // Terminal by construction: intent is cleared alongside publishing this,
        // so reaching here means a trigger raced the clear. Not retried — the
        // core has already refused this config — and not stopped either, which
        // would publish over the reason. Released: see [ReconcileAction.Release].
        is ConnectionState.Failed -> ReconcileAction.Release
    }
}

/**
 * `Stop`, unless stopping would erase a terminal failure.
 *
 * Answering `Stop` from [ConnectionState.Failed] is not a harmless no-op:
 * `stopTunnelAndService` publishes `Disconnected`, which lands **over** the
 * failure. `TunnelService.onRevoke`'s KDoc calls the `Revoked` reason "the one
 * piece of information the user needs", and deliberately skips
 * `super.onRevoke()` to preserve it — and this erased it anyway.
 *
 * It was reachable by an ordinary `NetworkChanged`: every terminal settlement
 * clears session intent, so `!wanted` was already true when the next trigger
 * arrived, and the `Failed` arm in [reconcile] was never reached. Observed on a
 * device during the §11 revocation row, where Home read `Disconnected` after
 * another VPN app took the route; the observation went unexplained for a day
 * because the row's other assertion — intent cleared — passed.
 *
 * **Declining is not free, and this KDoc used to claim it was.** The claim was
 * that a terminal settlement has already run its own teardown and called
 * `stopStartedService`, so there is no core, no TUN and no foreground
 * notification left for a `Stop` to clean up. That is true of the settlement's
 * *own* notification and start token. It stopped being true of a framework start
 * that arrives afterwards: `TunnelService.onStartCommand` puts the service back
 * into the foreground and records a fresh start id **before** this decision is
 * reached, so answering `Nothing` here left a foreground service showing a
 * notification over a dead session, with an unresolved start token and nothing
 * that would ever stop it. The service survives its own `stopSelfResult` while
 * `:main` is bound, so this is not hypothetical.
 *
 * [ReconcileAction.Release] is the third answer that was missing: hand both back
 * without publishing over the reason.
 *
 * **[ConnectionState.Disconnected] still stops, and that is load-bearing** —
 * do not fold it in with `Failed` as "already down". Spec §1.3 answers a sticky
 * restart by reconciling a null intent, and with nothing wanted the `Stop` is
 * what shuts the service down; that is the outcome `START_NOT_STICKY` used to
 * protect, reached by asking rather than by refusing. `Disconnected` carries no
 * reason to erase, so preserving it buys nothing.
 *
 * Exhaustive with no `else`, for the reason spec §2.2 gives for
 * `FailureReason.retryability`: a state added later must not be silently
 * absorbed by whichever branch happens to catch it.
 */
private fun stopUnlessItWouldEraseAFailure(actual: ConnectionState): ReconcileAction =
    when (actual) {
        is ConnectionState.Failed -> ReconcileAction.Release

        is ConnectionState.Connecting,
        is ConnectionState.Connected,
        is ConnectionState.Reconnecting,
        ConnectionState.Disconnected,
        ConnectionState.Disconnecting,
        -> ReconcileAction.Stop
    }

/**
 * Spec §2.3: the capped reason is the only one with a ceiling.
 *
 * **Neither `false` answer is reachable from a `Reconnecting` this app
 * published, and both are kept on purpose.** `TunnelService.settleRetryableFailure`
 * consults [nextAttemptExceedsCap] *before* publishing and settles as terminal
 * instead when the next attempt would reach the cap, so a published
 * `Reconnecting` always carries `attempt < TUN_ESTABLISH_ATTEMPT_CAP`; and only a
 * `Retryable` or `RetryableCapped` reason reaches that function at all, so the
 * terminal arm has no producer either. A prior review and a prior fix report both
 * listed the capped arm as a live "keeps `Nothing`, deliberately" case. It is not
 * one, and this says so rather than leaving the next reader to re-derive it.
 *
 * They stay because this function cannot see the rule that makes them
 * unreachable: that rule lives in a `VpnService` no JVM test can drive, so
 * dropping it would fail nothing here. A reader tracing *where the retry actually
 * stops* wants [nextAttemptExceedsCap], not this.
 */
private fun ConnectionState.Reconnecting.mayAttemptAgain(): Boolean =
    when (reason.retryability()) {
        Retryability.Retryable -> true
        Retryability.RetryableCapped -> attempt < TUN_ESTABLISH_ATTEMPT_CAP
        // Should not be reachable: a terminal reason never becomes Reconnecting.
        // Answering "no" rather than throwing keeps a mis-wire from spinning.
        Retryability.Terminal -> false
    }

/**
 * Whether the attempt a failure is about to publish should be settled as
 * terminal instead of another `Reconnecting` (spec §2.3).
 *
 * Without this, a `RetryableCapped` reason reaching [TUN_ESTABLISH_ATTEMPT_CAP]
 * would publish one more `Reconnecting` that [mayAttemptAgain] then permanently
 * refuses to retry — nothing converts that into `Failed`, so the UI would show
 * "reconnecting" forever over a session that has actually given up (spec §3.4
 * names this exact class of gap). `Retryable` is deliberately unbounded: an
 * unbounded retry while a network exists is the point of fail-closed (§2.4),
 * not a bug in it.
 */
internal fun nextAttemptExceedsCap(
    retryability: Retryability,
    nextAttempt: Int,
): Boolean = retryability == Retryability.RetryableCapped && nextAttempt >= TUN_ESTABLISH_ATTEMPT_CAP

/**
 * Whether the TUN outlives a failed session (spec §6.1).
 *
 * Retaining it is the whole kill switch: the interface already carries
 * `0.0.0.0/0` and `::/0`, and with nothing reading the fd a route to nothing is
 * a blackhole. Nothing is added to `establishTun` to achieve this.
 *
 * Two consequences, both correct and both easy to mistake for bugs. **DNS
 * blackholes too**, because the TUN is what advertises the resolver (§5.2,
 * lever 1) — which is also what makes the failure visible rather than silently
 * degraded. And **per-app deny-listed apps keep working**, because they are
 * outside the TUN by construction (§8); fail-closed cannot reach them.
 */
internal fun shouldRetainTun(
    failClosed: Boolean,
    intentWanted: Boolean,
    retryability: Retryability,
): Boolean =
    failClosed &&
        intentWanted &&
        when (retryability) {
            Retryability.Retryable, Retryability.RetryableCapped -> true
            // Nothing is retrying, so holding the TUN would leave the device
            // with no connectivity and nothing working to restore it.
            Retryability.Terminal -> false
        }
