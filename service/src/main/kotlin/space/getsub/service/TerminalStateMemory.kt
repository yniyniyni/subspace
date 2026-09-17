// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState
import java.util.concurrent.atomic.AtomicReference

/**
 * Remembers the last terminal [ConnectionState.Failed] for as long as the `:bg`
 * process lives, so it outlives the `TunnelService` instance that published it.
 *
 * ## The bug this exists to close (§11 row 7 / device check W2)
 *
 * `TunnelService.currentState` is a plain field seeded with
 * [ConnectionState.Disconnected]. When another VPN app takes the route,
 * `onRevoke` publishes `Failed(Revoked)` correctly and `onDestroy` preserves it
 * correctly — `currentState as? Failed ?: Disconnected` — and the user still sees
 * "Disconnected", because by the time they open the app the binder is answering
 * from a **new instance** whose field is back at the default.
 *
 * Observed on device 2026-09-16: the process survived (same pid) while the
 * `ServiceRecord` was replaced. So the revoke was never overwritten; it was
 * *forgotten*, one instance later. `onRevoke`'s KDoc — which explains at length why
 * `super.onRevoke()` is skipped so nothing can overwrite the Revoked state — is
 * defending against the wrong mechanism.
 *
 * ## Scope: one memory per `:bg` process, like [SessionIntentGate]
 *
 * `@Singleton` in [ServiceModule] is what makes this one per process rather than
 * one per service instance, and that is the entire point of the binding — the same
 * reasoning [SessionIntentGate]'s KDoc gives for the same scope.
 *
 * Deliberately **not** persisted. A revoke matters to the user who is looking at
 * the app shortly afterwards; a revoke from last week resurfacing as the current
 * state would be a worse lie than the one this fixes. Process death is therefore
 * the expiry rule, and it needs no timestamp, no schema change, and no policy about
 * when a remembered failure goes stale.
 *
 * ## Why an [AtomicReference] and not a `Mutex`
 *
 * [SessionIntentGate] serialises with a `Mutex` because its writes are suspending
 * Room calls. These are not: [record] is called from `publishLocked`, which already
 * runs under the service lock and is not `suspend`. A mutex here would be
 * unreachable ceremony at best and a suspension point in a non-suspending path at
 * worst.
 */
internal class TerminalStateMemory {
    private val remembered = AtomicReference<ConnectionState.Failed?>(null)

    /**
     * Records [state] when it is terminal, and **clears** the memory for every
     * other state.
     *
     * Clearing on any non-terminal publish is what makes staleness impossible
     * without a clock: the instant a new session reaches `Connecting`, the
     * remembered failure is gone, so it can never be shown over a session that has
     * since started. `Disconnected` clears it too — an explicit stop is the user
     * ending the session, not a failure they need reported back.
     */
    fun record(state: ConnectionState) {
        remembered.set(state as? ConnectionState.Failed)
    }

    /** The remembered terminal failure, or null when none is outstanding. */
    fun lastTerminal(): ConnectionState.Failed? = remembered.get()

    /**
     * What a freshly created `TunnelService` should seed `currentState` with.
     *
     * A new instance that seeds the remembered failure behaves exactly as the
     * destroyed instance would have: the two guards that read `currentState`
     * already handle `Failed`, so they see what they would have seen had the
     * instance survived — which is the property this whole class buys.
     */
    fun seedState(): ConnectionState = lastTerminal() ?: ConnectionState.Disconnected
}
