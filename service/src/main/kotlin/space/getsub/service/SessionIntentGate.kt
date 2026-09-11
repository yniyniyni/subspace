// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the `wanted` half of the session intent (spec §1.1) so that a settlement
 * cannot clear intent that belongs to a newer session.
 *
 * ## The bug this exists to close
 *
 * `TunnelService.settleTerminalFailure` clears intent from its `persist` lambda,
 * which `TerminalOutcome.settle` deliberately runs **after** releasing the
 * service lock — persistence must never be followed by a lifecycle transition,
 * and that is what buys it. `persist` then writes the failure row to Room and
 * clears intent, in that order:
 *
 * ```
 * connectionRecorder.record(rowId, failed)          // suspends
 * settingsRepository.setTunnelSessionWanted(false)
 * ```
 *
 * While that first write suspends, the command coordinator — a *different*
 * coroutine — can accept a new connect, which writes `wanted = true`. The line
 * below then overwrites it with `false`. The consequence is not cosmetic: the
 * `tunnelSessionWanted` collector unregisters the new session's `NetworkMonitor`,
 * [shouldRetainTun] reads `intentWanted = false` and releases the kill switch,
 * and every subsequent [reconcile] answers [ReconcileAction.Stop]. A live,
 * wanted session is torn down by the failure of the attempt before it.
 *
 * ## Why the generation counter cannot be the guard
 *
 * The obvious fix — re-check `gen == generation` before clearing — does not
 * work. `TunnelService.connectFromCommand` writes `wanted = true` *before*
 * calling `startTunnel`, and `startTunnel` is what bumps the generation. So
 * there is a window in which the new session's intent is already written and the
 * generation has not moved: the check passes, and the clear still lands on the
 * new session's intent.
 *
 * The token here moves at the same instant intent does, inside the same critical
 * section as the write, which is precisely the moment the generation counter
 * does not move.
 *
 * ## Two properties, both needed
 *
 * 1. **Ownership.** [clearIfOwned] writes only while the token it was handed is
 *    still current, so a settlement whose session has been superseded by an
 *    accepted connect clears nothing.
 * 2. **Serialisation.** The check and the clear happen inside [mutex], which
 *    [want] also holds across its own write. A connect's `wanted = true`
 *    therefore cannot interleave *between* the ownership check and the clear —
 *    it runs wholly before (and the token has moved, so the clear is refused) or
 *    wholly after (and its `true` is the last write). Ownership alone would
 *    leave that residual window open; the mutex is what closes it.
 *
 * ## Scope: one gate per `:bg` process
 *
 * Provided as a Hilt `@Singleton` by `ServiceModule`, so there is one per
 * `SingletonComponent`. Hilt keeps that component on the `@HiltAndroidApp`
 * `Application` object (`Hilt_SubspaceApplication.componentManager` is an
 * instance field), and Android creates one `Application` object per process —
 * `SubspaceApplication` runs in both `:main` and `:bg`, which is why its
 * `onCreate` checks which one it is in. `TunnelService` is injected from the
 * component of the process it runs in (Hilt's `ServiceComponentManager` reaches
 * it through the service's own `getApplication()`). So every `TunnelService`
 * instance a `:bg` process hosts gets this one object. `:main` never builds
 * one: a scoped binding is created on first request, and nothing there asks.
 *
 * That is the scope the ownership proof needs, and the per-instance gate this
 * replaced did not have it. The proof holds only if every accepted connect
 * moves the token a pending clear is checked against. A clear can outlive the
 * service instance that issued it: `TunnelService.settleTerminalFailure`'s
 * `persist` runs under `NonCancellable` precisely so `onDestroy`'s
 * `scope.cancel()` cannot drop it. While it is suspended, the system can create
 * a new `TunnelService` in the same process, and that instance can accept a
 * connect. With a gate per instance, the late clear consulted the destroyed
 * instance's gate — a token no connect would ever move again — so it always
 * matched, and wrote `false` over the new session's `true`. With one gate per
 * process, the new instance's [want] moves the token that clear checks.
 *
 * Nothing wider is needed. Room persists the intent, but a clear in flight
 * cannot outlive its process, so a gate that dies with the process takes
 * nothing with it.
 *
 * ## What it does not cover
 *
 * `BootReceiver` runs in `:main` and writes `wanted = true` directly, without
 * moving this token, so a terminal settlement in `:bg` that holds the current
 * token can still clear over that write. That a settlement cannot be in flight
 * at boot is not the reason it is harmless — always-on starts `:bg` around user
 * unlock, when `BOOT_COMPLETED` is dispatched, and a sticky restart can have it
 * running already.
 *
 * The reason is [reconcile]. A terminal settlement publishes `Failed` before it
 * clears, and from `Failed` `reconcile` never starts anything: it answers
 * [ReconcileAction.Nothing] whatever intent says, or [ReconcileAction.Stop] if
 * intent already reads false, since that check runs first. So the `true`
 * BootReceiver wrote could not have started a session from that state in any
 * case. What the clear costs is the persisted `true` for a later start, and
 * only when that boot's own session has just failed terminally — which is what
 * pre-gate code did too.
 *
 * A second writer of `wanted = true` added inside `:bg` would need to go through
 * [want]; that is the only reason this is not enforceable by the type system.
 *
 * Unconditional clears — an explicit disconnect, `onRevoke`, a rejected connect
 * — deliberately do **not** go through here. They run on the command
 * coordinator's own coroutine (or, for `onRevoke`, describe an event that has
 * already taken the route away), so no connect can interleave with them, and
 * their intent to clear is not conditional on anything.
 *
 * ## Why a separate class
 *
 * The same reason [TerminalOutcome] is one: `TunnelService` is a `VpnService`
 * and cannot be driven from a JVM test in this project (§10.7 does not justify
 * adding Robolectric or a mocking library for it). The ownership rule is the
 * part that can be wrong, so it lives where a test can drive a real concurrent
 * connect against a real suspended write.
 *
 * @property writeWanted `SettingsRepository::setTunnelSessionWanted`. Called
 *   only while [mutex] is held.
 */
internal class SessionIntentGate(
    private val writeWanted: suspend (Boolean) -> Unit,
) {
    private val mutex = Mutex()

    /**
     * Monotonic, and the only thing that identifies "which session's intent".
     *
     * Atomic rather than guarded by `TunnelService`'s lock: [currentToken] is
     * read from inside that lock, and taking a second monitor underneath it
     * would add a lock ordering to reason about for a value that needs nothing
     * but visibility.
     */
    private val token = AtomicInteger(0)

    /**
     * The token a session started now would own.
     *
     * Read by `TunnelService.startTunnel` under its own lock, ahead of its
     * already-active guard, so both of its branches record it — the one that
     * starts a session and bumps the generation, and the fold that absorbs a
     * connect into the live session without a bump. That read cannot race a
     * later [want]: both of `startTunnel`'s callers reach it from the command
     * coordinator's single consumer coroutine, and `startTunnel` does not
     * suspend, so the next connect's [want] cannot begin until it returns.
     */
    fun currentToken(): Int = token.get()

    /**
     * Spec §1.2: intent goes true when a connect is **accepted**, not when it
     * succeeds — and the token moves with it, in the same critical section.
     */
    suspend fun want() {
        mutex.withLock {
            token.incrementAndGet()
            writeWanted(true)
        }
    }

    /**
     * Clears intent only if [ownedToken] is still the current session's.
     *
     * @return true if the clear was written, false if a newer accepted connect
     *   has taken the intent since [ownedToken] was minted — in which case
     *   nothing is written at all.
     */
    suspend fun clearIfOwned(ownedToken: Int): Boolean =
        mutex.withLock {
            if (token.get() != ownedToken) {
                false
            } else {
                writeWanted(false)
                true
            }
        }
}
