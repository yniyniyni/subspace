// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.failure
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "TerminalStateMemory"

/**
 * Remembers the last terminal [ConnectionState.Failed] for as long as the `:bg`
 * process lives, so it outlives the `TunnelService` instance that published it —
 * and, since Task 20 (ARCHITECTURE.md §11 row 7, part 2), persists it through
 * [persistence] so it outlives the *process* too.
 *
 * ## The bug this exists to close (ARCHITECTURE.md §11 row 7 / device check W2)
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
 * ## Task 20: the same defect, one layer up
 *
 * A device run (Task 19c) found the process itself can die between the revoke and
 * the user reopening the app — swiping Subspace from recents kills `:bg` once the
 * revoke teardown has already ended the foreground service, and ActivityManager is
 * then free to do so. [remembered] is a plain in-process field, so it does not
 * survive that; the next process starts with nothing remembered and seeds
 * `Disconnected` all over again. [persistence] is what closes that: every change
 * [remembered] makes is mirrored to Room, and [loadPersisted] is what a brand-new
 * process reads back.
 *
 * Deliberately **not** promoted to a timestamped, indefinitely-retained record. A
 * revoke matters to the user who is looking at the app shortly afterwards; a
 * revoke from last week resurfacing as the current state would be a worse lie than
 * the one this fixes. Any non-terminal publish — the next `Connecting` included —
 * still clears both [remembered] and the persisted row, so staleness needs no
 * clock: it is simply impossible for a failure to outlive the next session that
 * starts.
 *
 * ## Scope: one memory per `:bg` process, like [SessionIntentGate]
 *
 * `@Singleton` in [ServiceModule] is what makes this one per process rather than
 * one per service instance, and that is the entire point of the binding — the same
 * reasoning [SessionIntentGate]'s KDoc gives for the same scope. [scope] is this
 * class's own, not any one `TunnelService` instance's: see the note on [scope]
 * below for why that distinction is load-bearing now that there is a write to
 * carry out, not just a field to read.
 *
 * ## Why an [AtomicReference] and not a `Mutex`
 *
 * [SessionIntentGate] serialises with a `Mutex` because its writes are suspending
 * Room calls. [remembered]'s own update is not: [record] is called from
 * `publishLocked`, which already runs under the service lock and is not
 * `suspend`. A mutex here would be unreachable ceremony at best and a suspension
 * point in a non-suspending path at worst. The Room write *is* suspending, which
 * is exactly why it is queued onto [writes] and run on [scope] instead of awaited
 * inline — see [record]'s KDoc.
 *
 * @property persistence the Room-backed seam [record] writes through and
 *   [loadPersisted] reads through. A seam, not [space.getsub.core.data.SettingsRepository]
 *   directly, so this class stays unit-testable with a fake — `TunnelService` is a
 *   `VpnService` and this project carries no Robolectric (ARCHITECTURE.md §10.7).
 * @property scope **This class's own scope, not a `TunnelService` instance's.**
 *   A `record` call can be the very last thing a dying instance does — `onRevoke`
 *   publishes `Failed(Revoked)` and the system can tear the instance down moments
 *   later — and the write that makes it durable must not be cancelled by that
 *   instance's `onDestroy`. [ServiceModule] constructs one scope here, scoped to
 *   the `@Singleton` binding's own process lifetime, for exactly the reason
 *   [SessionIntentGate] is itself process-scoped rather than per-instance.
 * @property logError how a Room failure is reported. Real callers never pass
 *   this — the default is `Log.e`. The seam exists for the same reason
 *   `fetchMetricsPayload`'s `warn` parameter does: this class's unit tests run
 *   as plain JVM tests with no Android framework mocked in, and the stub
 *   `android.util.Log` throws rather than no-oping when called from one.
 */
internal class TerminalStateMemory(
    private val persistence: TerminalFailurePersistence,
    private val scope: CoroutineScope,
    private val logError: (String) -> Unit = { msg -> Log.e(TAG, msg) },
) {
    private val remembered = AtomicReference<ConnectionState.Failed?>(null)

    /**
     * One pending write, ordered and coalesced — see [consumeWrites] for what runs
     * on the far end.
     *
     * [Channel.CONFLATED], not an unbounded buffer: [record] cannot suspend (it
     * runs under `TunnelService`'s lock), so enqueuing must never block, and a
     * capacity-1 "keep only the newest" buffer is exactly what the never-stale
     * design calls for anyway. If a write for an older fact is still queued (not
     * yet picked up) when a newer one arrives, the newer one simply replaces it in
     * the buffer — nothing is lost that still matters, because by the time a
     * superseded value would have reached Room the field it would have written is
     * already wrong. What [Channel.CONFLATED] does **not** do is reorder or drop a
     * write that is already *in flight*: see [consumeWrites].
     */
    private val writes = Channel<PersistedFailure?>(Channel.CONFLATED)

    init {
        scope.launch { consumeWrites() }
    }

    /**
     * Drains [writes] one at a time, in the order they were enqueued, for as long
     * as [scope] lives.
     *
     * This is the piece that keeps a quick `Failed → clear` from landing out of
     * order. A single coroutine ever calls [TerminalFailurePersistence.save]; the
     * `for` loop does not advance to the next queued value until the current
     * [TerminalFailurePersistence.save] call returns, so a slow write for an older
     * fact is never overtaken by a newer one that was enqueued while it was still
     * in flight — the newer one simply waits in [writes]' one-slot buffer (and, if
     * a third arrives before the first finishes, replaces it there, which is safe
     * for the same reason noted on [writes]). An independent `scope.launch` per
     * [record] call — the alternative this deliberately avoids — would give no
     * such guarantee: two suspending Room writes dispatched to the same
     * multi-threaded dispatcher can complete in either order, and the one that
     * finishes last decides what Room remembers.
     *
     * A write failure is logged and dropped, never rethrown: a Room error here must
     * not reach [scope]'s `CoroutineExceptionHandler`, if it has one bound at all,
     * since nothing about a failed *persist* should affect the live tunnel (ARCHITECTURE.md §10.4) —
     * the in-memory [remembered] already has the fact right regardless.
     */
    private suspend fun consumeWrites() {
        for (next in writes) {
            runCatching { persistence.save(next) }
                .onFailure { e ->
                    // ARCHITECTURE.md §5.6: the class name only, never the message.
                    logError("failed to persist terminal failure: ${e.javaClass.simpleName}")
                }
        }
    }

    /**
     * Records [state] when it is terminal, and **clears** the memory for every
     * other state.
     *
     * Clearing on any non-terminal publish is what makes staleness impossible
     * without a clock: the instant a new session reaches `Connecting`, the
     * remembered failure is gone, so it can never be shown over a session that has
     * since started. `Disconnected` clears it too — an explicit stop is the user
     * ending the session, not a failure they need reported back.
     *
     * **Writes to [persistence] only when the remembered fact actually changes.**
     * `publishLocked` calls this on *every* publish, including the once-a-second
     * health-transition republishes `HealthDetector` drives while a session sits
     * `Connected` — persisting on every one of those would turn a steady session
     * into a steady stream of Room writes for a fact ([ConnectionState.Failed]'s
     * absence) that never moved. [ConnectionState.Failed]'s own `equals` is what
     * makes the re-recorded-same-failure case a no-op too: two [failure] calls
     * with the same reason and detail produce equal instances, so retrying the
     * same terminal state again is indistinguishable from not calling [record] at
     * all.
     *
     * Never suspends: called from `publishLocked`, under `TunnelService`'s lock.
     * [AtomicReference.getAndSet] decides synchronously whether anything changed;
     * the write itself is handed to [writes], never awaited here.
     */
    fun record(state: ConnectionState) {
        val next = state as? ConnectionState.Failed
        val previous = remembered.getAndSet(next)
        if (previous == next) return
        writes.trySend(next?.let { PersistedFailure(it.reason, it.detail) })
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
     *
     * In-memory only, and still synchronous for exactly that reason: a brand-new
     * `:bg` process has nothing in [remembered] yet, however long ago [persistence]
     * last changed, so this seeds `Disconnected` and `onCreate` separately launches
     * [loadPersisted] to recover the Room-persisted fact. `onCreate` must not
     * `runBlocking` to read it inline (ARCHITECTURE.md §12: no `runBlocking` outside
     * tests) — see `persistedSeedPublication` for the guard around publishing what
     * [loadPersisted] eventually returns, and [syncPersisted] for what reconciles
     * Room when that guard declines.
     */
    fun seedState(): ConnectionState = lastTerminal() ?: ConnectionState.Disconnected

    /**
     * Reconciles the persisted row against [current]'s real terminal fact, for the
     * one case [record] cannot reach on its own: `onCreate`'s asynchronous seed
     * read came back with [priorPersisted] from the *previous* process, but
     * `persistedSeedPublication` declined to publish it because this process had
     * already moved on by the time the read returned.
     *
     * Without this, that staleness can outlive every future session in this
     * process. [remembered] is still null at that point — this process has never
     * written to it — so if the state that superseded the seed was itself
     * non-terminal (a `Connecting` published while the Room read was still in
     * flight, say), [record] saw `null -> null`: a no-op by its own "only on
     * change" rule, because nothing in *this process's memory* looks different.
     * [priorPersisted] is not in [remembered] though, it is in Room, left there by
     * a process that no longer exists — so the row survives untouched, and a
     * later cold open can still show it.
     *
     * The fix is to seed [remembered] with [priorPersisted] — the fact Room
     * currently, actually holds — immediately before asking [record] to reconcile
     * against [current]. That turns "no change" into a real one wherever [current]
     * disagrees with [priorPersisted], so the ordinary write-on-change path (the
     * same [writes] channel, the same ordering guarantee) emits whatever is
     * correct for [current] — usually a clear, but a different `Failed` if a real
     * new failure raced in between, in which case the write is redundant (Room
     * already holds it, from that failure's own [record] call) but still correct.
     *
     * @param priorPersisted what [loadPersisted] returned — the previous process's
     *   persisted failure. Only meaningful, and only called, when that read found
     *   something; a null read needs no reconciliation (see call site).
     * @param current `currentState`, read fresh under the lock, after the guard
     *   has already declined to publish [priorPersisted] over it.
     */
    fun syncPersisted(
        priorPersisted: ConnectionState.Failed,
        current: ConnectionState,
    ) {
        remembered.set(priorPersisted)
        record(current)
    }

    /**
     * Reads [persistence] for the failure a *previous* `:bg` process persisted.
     *
     * Reconstructs through [failure] rather than building
     * [ConnectionState.Failed] directly — its constructor is private for exactly
     * this reason (ARCHITECTURE.md §5.6) — which re-redacts [PersistedFailure.detail].
     * That is a no-op on the text this class ever persists: [redact] is
     * idempotent by construction (`core/model/Redaction.kt`), and every detail
     * reaching [record] already passed through it once, in [ConnectionState.Failed]'s
     * own constructor, before this class ever saw it.
     *
     * Null for "nothing to seed" covers three cases alike, deliberately: no
     * failure was ever persisted, a row was persisted but
     * [TerminalFailurePersistence.load] could not make sense of it (an unknown
     * `FailureReason` name), and the read itself failed. Callers do not need to,
     * and must not, tell any of these apart — ARCHITECTURE.md §10.4 says the wrong
     * answer here is announcing a specific failure that was never real, and a Room
     * read failure is certainly not one.
     *
     * A failed read must not become a *published* one either. Before this guard,
     * a [persistence] exception reached `TunnelService.errorHandler` — the
     * scope's `CoroutineExceptionHandler`, built for a start-sequence crash — which
     * published `Failed(CoreStartFailed)` for a Room hiccup that was never a real
     * tunnel failure, and [record] then persisted *that* fabrication right back to
     * Room. Caught and logged here instead, the same way [consumeWrites] handles a
     * failed write: a Room error must never reach the tunnel's own error handling
     * (ARCHITECTURE.md §10.4).
     *
     * [CancellationException] is rethrown ahead of the broader catch, never
     * logged as a failure — `TerminalOutcome`/[ConnectionRecorder]'s KDoc has the
     * same note: it is an [Exception] too, so a bare `catch (e: Exception)` would
     * otherwise swallow this instance's `onDestroy` cancelling [scope] mid-read and
     * let this coroutine carry on as if the read had simply come back empty.
     */
    // TooGenericExceptionCaught: ARCHITECTURE.md §10.4 — a Room read failure must never
    // reach the tunnel's own error handling.
    @Suppress("TooGenericExceptionCaught")
    suspend fun loadPersisted(): ConnectionState.Failed? {
        val persisted =
            try {
                persistence.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ARCHITECTURE.md §5.6: the class name only, never the message.
                logError("failed to read persisted terminal failure: ${e.javaClass.simpleName}")
                null
            }
        return persisted?.let { failure(it.reason, it.detail) }
    }
}
