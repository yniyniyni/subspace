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
 * clears, and from `Failed` `reconcile` never answers [ReconcileAction.Start],
 * whatever intent says. So the `true` BootReceiver wrote could not have started a
 * session from that state in any case. What the clear costs is the persisted
 * `true` for a later start, and only when that boot's own session has just failed
 * terminally — which is what pre-gate code did too.
 *
 * Only "never starts anything" is load-bearing here, and it has held throughout.
 * The arm named has not, and this is the fourth correction: the sentence said
 * `Nothing`-or-`Stop` when it was written, `a4e3e14` made both arms answer
 * `Nothing`, the third outcome made both answer `Release` — and `Release` was
 * wrong too, for [ReconcileTrigger.NetworkLost], which returns `Nothing` from
 * spec §2.4's early return before intent is read
 * (`ReconcileTest.anUnwantedFailureSurvivesEveryTrigger` pins both halves).
 * Naming the property the argument needs, rather than whichever arm happens to
 * deliver it, is what stops a fifth.
 *
 * A second writer of `wanted = true` added inside `:bg` would need to go through
 * [want]; that is the only reason this is not enforceable by the type system.
 *
 * ## Every write in `:bg` goes through here
 *
 * The gate protects a live session only if every write that can clear intent
 * takes [mutex]. A direct repository write does not: it can land after a newer
 * session's [want] and clobber it, and no token check ever sees it. So in `:bg`
 * the repository's `setTunnelSessionWanted` has exactly one caller —
 * [writeWanted], under [mutex] — and every clear is [clearIfOwned], with a
 * token captured while the session being ended was still the live one:
 *
 *  - **A start-sequence settlement or rejection** uses the token
 *    `TunnelService` recorded for that session, read under its lock in the
 *    same region that confirms the generation is still current — never read
 *    from here at clear time, which could already name a newer connect.
 *  - **`onRevoke`** uses [currentToken], read synchronously before its clear is
 *    launched. A connect accepted afterwards moves the token and the late
 *    clear is refused, rather than ending the session that connect starts.
 *  - **A disconnect, a rejected connect, and a reconcile that finds nothing to
 *    connect to** use [currentToken] (or [want]'s return) read on the command
 *    coordinator. [want]'s only caller runs there too, so nothing can move the
 *    token between that read and the clear: these are never refused, and are
 *    unconditional in effect. They come here anyway, so that "only the gate
 *    writes" holds without exceptions to remember and every clear is ordered
 *    by the same mutex.
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
     * The token of the most recently accepted connect.
     *
     * Not how a starting session learns its own token — that is [want]'s return
     * value, which cannot name a different connect. This is for a clear with no
     * session-recorded token to hand: `onRevoke`, which reads it synchronously
     * before launching its clear, and the command coordinator's own clears,
     * where no [want] can interleave (see the class KDoc).
     */
    fun currentToken(): Int = token.get()

    /**
     * Spec §1.2: intent goes true when a connect is **accepted**, not when it
     * succeeds — and the token moves with it, in the same critical section.
     *
     * @return the token this call minted, taken inside that critical section.
     *   The session this connect starts owns exactly this value. Reading
     *   [currentToken] after this returns would not be equivalent: the mutex is
     *   already released by then, and a second connect let in by that release
     *   could have moved the token first — the first session would then record
     *   the second's token, and its settlement could clear the second's intent.
     */
    suspend fun want(): Int =
        mutex.withLock {
            val minted = token.incrementAndGet()
            writeWanted(true)
            minted
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
