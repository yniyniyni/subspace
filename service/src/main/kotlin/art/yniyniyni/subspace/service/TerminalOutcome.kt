// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.ConnectionState

/**
 * Commits a start sequence's terminal outcome — [ConnectionState.Connected] or
 * [ConnectionState.Failed] — in an order that a concurrent teardown or a newer start cannot
 * interleave with.
 *
 * ## The invariant
 *
 * > Once an outcome has been published, persistence must never be followed by a lifecycle
 * > mutation from that generation.
 *
 * ## Why this exists
 *
 * §5.4 says teardown is reachable from three places that are not serialised with each other,
 * and [TunnelService]'s `generation` counter is what supersedes an in-flight start. Both
 * terminal paths used to check that counter, publish, suspend to persist the outcome, and
 * *then* mutate lifecycle state — leaving a window in which a stale coroutine resumed and
 * reasserted lifecycle it no longer owned (PR #4 review, P1 finding A):
 *
 *  - The connected path published `Connected`, suspended in the write, and called
 *    `goForeground()` afterwards. A teardown during the write incremented `generation`,
 *    stopped the tunnel, removed the notification and published `Disconnected` — then the
 *    stale coroutine restored the *connected* foreground notification. That is §5.5's lying
 *    UI: an app showing a live tunnel over a tunnel that is down.
 *  - The failed path published `Failed`, suspended, then called `stopForeground()` and
 *    `stopSelf()`. A newer connection starting during the write inherited both: its
 *    foreground state removed, or its service stopped, by the previous attempt's failure.
 *
 * [settle] closes both by making the generation check and the lifecycle mutation one
 * transition under the same lock, with persistence strictly after and structurally unable to
 * touch lifecycle — the `persist` lambda gets no lifecycle access to misuse.
 *
 * ## Why a separate class
 *
 * Not for tidiness: it is what makes the ordering testable. [TunnelService] is a
 * `VpnService`, so its start sequence cannot run in a JVM test, and this project carries no
 * Robolectric or mocking library to stand one up (§10.7 does not justify adding one here).
 * Passing the lifecycle mutation in as a lambda lets `TerminalOutcomeTest` drive a delayed
 * write against a real concurrent teardown with fakes, while Android and native tunnel
 * behaviour stay in [TunnelService]. §10.1: the alternative was a fix asserted by reading.
 *
 * @property lock [TunnelService]'s own monitor. The check and the mutation must happen under
 *   the *same* lock that teardown takes, or they are simply two races instead of one.
 * @property currentGeneration reads `TunnelService.generation`. Called only while [lock] is
 *   held.
 * @property publish `TunnelService.publishLocked` — likewise only under [lock], which is
 *   what `RemoteCallbackList`'s non-reentrant broadcast requires (§5.4).
 */
internal class TerminalOutcome(
    private val lock: Any,
    private val currentGeneration: () -> Int,
    private val publish: (ConnectionState) -> Unit,
) {
    /**
     * Commits [state] for generation [gen], then records it.
     *
     * Ordering, which is the whole point of this function:
     *
     *  1. Under [lock]: verify [gen] is still current, run [lifecycle], publish [state].
     *     No suspension point separates the check from the mutation, so nothing can
     *     supersede [gen] between them.
     *  2. Outside the lock, and only if step 1 committed: [persist].
     *
     * [lifecycle] must be the generation's *whole* lifecycle mutation — establishing the
     * connected notification, or clearing a failed attempt's foreground and started state.
     * Anything left for after the call is exactly the bug this class was written to fix.
     *
     * It runs under [lock], so it must not suspend, and it must not be *slow*: §5.4 keeps the
     * native teardown calls (`Tun2Socks.stop`, `XrayController.stopBlocking`) outside the lock
     * precisely so a wedged one cannot block state publication forever. Framework calls
     * belong here and native ones do not — `startForeground`/`stopForeground`/`stopSelf` are
     * bounded binder round trips to `system_server`, of the same kind `publish` itself already
     * makes when it broadcasts over `RemoteCallbackList`, and `stopSelf()` schedules
     * `onDestroy` on the main thread rather than running it inline, so it cannot re-enter this
     * lock from the calling thread.
     *
     * [persist] is deliberately given nothing but its own body — it cannot start or stop
     * foreground state, because it is handed no means to. A write failure inside it must not
     * take the tunnel down (§10.4); [ConnectionRecorder] already owns that, and cancellation
     * propagates rather than being mistaken for a failed write.
     *
     * @return `true` if the transition committed, `false` if [gen] had already been
     *   superseded — in which case neither [lifecycle] nor [persist] ran, and the caller must
     *   abandon its sequence immediately. There is no outcome to record for a generation that
     *   no longer owns the tunnel: the generation that superseded it publishes its own.
     */
    suspend fun settle(
        gen: Int,
        state: ConnectionState,
        lifecycle: () -> Unit,
        persist: suspend () -> Unit,
    ): Boolean {
        synchronized(lock) {
            if (gen != currentGeneration()) return false
            // Lifecycle first, then publication: the state the UI is told about must
            // already be true of the service when it hears it.
            lifecycle()
            publish(state)
        }
        // Past this line nothing may mutate lifecycle for `gen`. A teardown or a newer
        // start can and will run during this suspension, and it owns the service now.
        persist()
        return true
    }
}
