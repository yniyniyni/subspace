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

    if (!intent.wanted) return ReconcileAction.Stop

    // Wanted, but the row it named is gone. Nothing to start, and holding a
    // foreground service open for a profile that does not exist helps nobody.
    val rowId = intent.profileRowId ?: return ReconcileAction.Stop

    return when (actual) {
        // A start already in flight must not be started on top of itself; the
        // generation counter would supersede one of them anyway, wastefully.
        is ConnectionState.Connecting, ConnectionState.Disconnecting -> ReconcileAction.Nothing

        ConnectionState.Disconnected -> ReconcileAction.Start(rowId)

        is ConnectionState.Connected ->
            if (trigger == ReconcileTrigger.NetworkChanged) ReconcileAction.Restart(rowId) else ReconcileAction.Nothing

        is ConnectionState.Reconnecting ->
            if (actual.mayAttemptAgain()) ReconcileAction.Start(rowId) else ReconcileAction.Nothing

        // Terminal by construction: intent is cleared alongside publishing this,
        // so reaching here means a trigger raced the clear. Do nothing rather
        // than retry a config the core has already refused.
        is ConnectionState.Failed -> ReconcileAction.Nothing
    }
}

/** Spec §2.3: the capped reason is the only one with a ceiling. */
private fun ConnectionState.Reconnecting.mayAttemptAgain(): Boolean =
    when (reason.retryability()) {
        Retryability.Retryable -> true
        Retryability.RetryableCapped -> attempt < TUN_ESTABLISH_ATTEMPT_CAP
        // Should not be reachable: a terminal reason never becomes Reconnecting.
        // Answering "no" rather than throwing keeps a mis-wire from spinning.
        Retryability.Terminal -> false
    }
