// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState

/**
 * D4: decides when [TunnelClient]'s cached `_traffic` sample has outlived the
 * session it was reported for.
 *
 * [TunnelClient.traffic]'s own KDoc states the invariant: null until the
 * current session's first sample, and null again once that session ends. The
 * bug this class fixes was `_traffic` only ever being cleared in `unbind()`
 * and `onServiceDisconnected` — never in `onStateChanged` — so an ordinary
 * disconnect while the UI stayed bound left the previous session's sample,
 * `perTag` rows included, sitting in the flow for the next session's first
 * tick to compose against.
 *
 * **Why this is not simply "clear whenever the new state is not `Connected`,
 * and also whenever a `Connected` arrives with a different
 * `sinceEpochMillis`."** That reads naturally from the state *names*, and it
 * is wrong for a retained-TUN restart (a Wi-Fi<->cellular handoff, spec
 * §1.4/§1.5) — verified by reading `TunnelService`, not assumed:
 *
 * - `TunnelService.restartCoreRetainingTun` / `attachRetainedTun` /
 *   `attachTun` publish `Connecting(AllocatingPort)` and
 *   `Connecting(StartingTunnel)` on the way to the restarted `Connected`, and
 *   `publishLocked` broadcasts every one of those to every bound client —
 *   there is no coalescing. So a retained-TUN restart genuinely produces
 *   `Connected -> Connecting -> Connecting -> Connected` on this client's
 *   `onStateChanged`, not a state that "stays Connected" the way the name
 *   alone suggests.
 * - Both of those `Connected` settlements stamp
 *   `ConnectionState.Connected(System.currentTimeMillis(), ...)` fresh, every
 *   time, retained restart or not. `sinceEpochMillis` is a display timestamp,
 *   not a stable session id across a `Restart` reconcile action.
 *
 * Naively applying either literal rule above would clear `_traffic` at the
 * `Connecting` step of every retained-TUN restart (rule 1) or at the restart's
 * final `Connected` (rule 2, since its `sinceEpochMillis` always differs from
 * the one before it) — exactly the tiles-blank-on-a-network-handoff regression
 * a fix here must not introduce, even though `TrafficSampler`'s own running
 * total is never reset or recreated across that same restart (spec §1.4: "the
 * session total continues rather than resetting").
 *
 * **What actually identifies a session boundary.** `TunnelService.reconcile`
 * only ever returns `Restart` from an actual `Connected` state (see the
 * invariant comment in `restartCoreRetainingTun`), so in this codebase the
 * *only* way to reach `Connecting` or `Connected` without first passing
 * through [ConnectionState.Disconnected], [ConnectionState.Disconnecting],
 * [ConnectionState.Reconnecting] or [ConnectionState.Failed] is a restart of
 * an already-live session. Those four states are the ones that actually mean
 * "no live tunnel to report traffic for" — everything else, however it is
 * spelled, is either that same session continuing or a transition whose
 * traffic was already cleared when one of those four states was last
 * observed. Tracking session liveness through the *edges* the reconcile state
 * machine actually has, rather than pattern-matching a single incoming
 * state's name, is what survives the retained-TUN-restart case.
 */
internal class TrafficSessionTracker {
    /**
     * True once a [ConnectionState.Connected] has been observed and no state
     * meaning "no live tunnel to report traffic for" has been observed since.
     */
    private var sessionAlive = false

    /**
     * @return true when [state] continues the session already being tracked —
     *   the caller must leave its cached traffic sample alone. False means a
     *   session boundary: the caller must clear the sample before applying
     *   [state].
     */
    fun observe(state: ConnectionState): Boolean {
        val continuesSession =
            when (state) {
                is ConnectionState.Connected -> sessionAlive
                is ConnectionState.Connecting -> sessionAlive
                ConnectionState.Disconnected,
                ConnectionState.Disconnecting,
                is ConnectionState.Reconnecting,
                is ConnectionState.Failed,
                -> false
            }
        sessionAlive = state is ConnectionState.Connected || (state is ConnectionState.Connecting && sessionAlive)
        return continuesSession
    }

    /**
     * Forces the next [observe] to report a boundary, without needing a
     * [ConnectionState] to route through [observe] itself.
     *
     * [TunnelClient.unbind] and its `onServiceDisconnected` clear `_traffic`
     * unconditionally (§5.5: this client's idea of the session is worthless
     * once it can no longer hear from it) without necessarily having a fresh
     * [ConnectionState] on hand to `observe` — `unbind()` does not touch
     * [TunnelClient.state] at all. Calling this keeps the tracker's notion of
     * "is a session live" from drifting out of sync with `_traffic` itself.
     */
    fun reset() {
        sessionAlive = false
    }
}
