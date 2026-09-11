// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers [SessionIntentGate], the seam extracted for the stale-clear defect.
 *
 * The bug: `TunnelService.settleTerminalFailure` clears session intent from its
 * `persist` lambda, which runs *outside* the service lock and *after* a
 * suspending Room write. While that write suspends, the command coordinator — a
 * different coroutine — can accept a new connect and write `wanted = true`. The
 * clear then overwrote it with `false`, so the `tunnelSessionWanted` collector
 * unregistered the new session's `NetworkMonitor`, `shouldRetainTun` read
 * `intentWanted = false` and released the kill switch, and every subsequent
 * `reconcile` answered `Stop`: a live, wanted session torn down by the failure of
 * the attempt before it.
 *
 * Re-checking `gen == generation` does not fix it, which is the reason this class
 * exists at all: `connectFromCommand` writes `wanted = true` *before* calling
 * `startTunnel`, and `startTunnel` is what bumps the generation — so the new
 * session's intent can already be persisted while the generation check still
 * passes. That ordering is not reproducible here (it lives in `TunnelService`,
 * which is a `VpnService` and cannot be driven from a JVM test in this project);
 * what is reproducible, and what these tests drive, is the rule that replaces it.
 *
 * Properties under test:
 *
 *  1. **Ownership** — a clear whose token has been superseded writes nothing.
 *  2. **Serialisation** — a connect's write cannot land between the ownership
 *     check and the clear.
 *  3. **Same critical section** — the token has moved before `want()` lets the
 *     next waiter in, so no clear can see the new `true` with the old token.
 *  4. **`want()` returns its own token** — the value a starting session records
 *     is the one its own write minted, never one a later connect minted.
 *
 * 3 and 4 need a waiter that runs *inside* `want()`'s unlock, which `runTest`'s
 * dispatcher never produces (it only schedules a resumed waiter); both use
 * `Dispatchers.Unconfined` for that — see the first of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionIntentGateTest {
    /**
     * Stands in for `SettingsRepository.setTunnelSessionWanted`.
     *
     * Records a transcript as well as the final value: the second property is
     * about *ordering*, and an assertion on the end state alone would pass for a
     * sequence that briefly did the wrong thing on the way there. [beforeWrite]
     * is the hook that lets a test park a write mid-flight, the way a real Room
     * write suspends.
     */
    private class RecordingIntent {
        val transcript = mutableListOf<String>()
        var wanted: Boolean? = null
            private set
        var beforeWrite: suspend (Boolean) -> Unit = {}

        suspend fun write(value: Boolean) {
            transcript += "enter:$value"
            beforeWrite(value)
            wanted = value
            transcript += "wrote:$value"
        }
    }

    @Test
    fun `an accepted connect persists the intent and takes ownership of it`() =
        runTest {
            val store = RecordingIntent()
            val gate = SessionIntentGate(store::write)

            gate.want()

            store.wanted shouldBe true
            // A session started now owns what that write established. Zero is the
            // pre-connect value, so an implementation that never moved the token
            // would fail here.
            gate.currentToken() shouldBe 1
        }

    @Test
    fun `the settlement of the session that owns the intent clears it`() =
        runTest {
            val store = RecordingIntent()
            val gate = SessionIntentGate(store::write)
            gate.want()
            val owned = gate.currentToken()

            gate.clearIfOwned(owned) shouldBe true

            store.wanted shouldBe false
            store.transcript shouldBe listOf("enter:true", "wrote:true", "enter:false", "wrote:false")
        }

    @Test
    fun `a connect accepted while the failure row is being written keeps its intent`() =
        runTest {
            // The exact race in the report, in the shape `settleTerminalFailure`
            // has it: record the failure (suspends), then clear.
            val store = RecordingIntent()
            val gate = SessionIntentGate(store::write)
            gate.want()
            val failingSession = gate.currentToken()

            val recordingStarted = CompletableDeferred<Unit>()
            val letRecordingFinish = CompletableDeferred<Unit>()
            var cleared: Boolean? = null

            val settlement =
                launch {
                    recordingStarted.complete(Unit)
                    letRecordingFinish.await()
                    cleared = gate.clearIfOwned(failingSession)
                }

            recordingStarted.await()
            // The command coordinator accepts a new connect during the write.
            gate.want()
            letRecordingFinish.complete(Unit)
            settlement.join()

            cleared shouldBe false
            // The new session's intent survives, and the refused clear wrote
            // nothing at all — not even a redundant value.
            store.wanted shouldBe true
            store.transcript shouldBe listOf("enter:true", "wrote:true", "enter:true", "wrote:true")
        }

    @Test
    fun `a connect cannot write its intent between the ownership check and the clear`() =
        runTest {
            // Ownership alone leaves this residual: the check passes, and only
            // then does the connect write `true`, which the clear overwrites.
            // Holding the gate across both is what closes it.
            val store = RecordingIntent()
            val gate = SessionIntentGate(store::write)
            gate.want()
            val owned = gate.currentToken()

            val clearReachedWrite = CompletableDeferred<Unit>()
            val letClearFinish = CompletableDeferred<Unit>()
            store.beforeWrite = { value ->
                if (!value) {
                    clearReachedWrite.complete(Unit)
                    letClearFinish.await()
                }
            }

            val clearing = launch { gate.clearIfOwned(owned) }
            clearReachedWrite.await()
            val connecting = launch { gate.want() }
            runCurrent()

            // The connect is blocked on the gate: it has not entered its write.
            store.transcript shouldBe listOf("enter:true", "wrote:true", "enter:false")

            letClearFinish.complete(Unit)
            clearing.join()
            connecting.join()

            store.transcript shouldBe
                listOf("enter:true", "wrote:true", "enter:false", "wrote:false", "enter:true", "wrote:true")
            // The connect's write is the last one, so the new session is wanted.
            store.wanted shouldBe true
        }

    @Test
    fun `the token has moved by the time a waiting clear is let in`() =
        runTest {
            // Pins the "same critical section" half of the ownership proof: the
            // token must move before `want()` releases the mutex, not after it.
            // With the increment moved below `withLock`, a clear waiting on the
            // mutex is let in while the connect's `true` is already written and
            // the token still names the previous session — the check passes and
            // the clear overwrites the connect.
            //
            // Tests 3 and 4 cannot see that regression: on `runTest`'s
            // dispatcher a resumed waiter is only *scheduled*, so the connecting
            // coroutine always reaches its increment before the clear runs.
            // The clear here resumes on `Dispatchers.Unconfined`, which runs a
            // resumed continuation inline in the thread that resumed it — inside
            // `want()`'s own `unlock()`, before `want()` reaches the next statement.
            // That is the window a second thread would hit on a real dispatcher.
            val store = RecordingIntent()
            val gate = SessionIntentGate(store::write)
            gate.want()
            val previousSession = gate.currentToken()
            store.transcript.clear()

            var cleared: Boolean? = null
            var settlementQueued = false
            store.beforeWrite = { value ->
                if (value && !settlementQueued) {
                    settlementQueued = true
                    // The previous session's settlement reaches the gate while
                    // the new connect holds it, mid-write.
                    launch(Dispatchers.Unconfined) { cleared = gate.clearIfOwned(previousSession) }
                }
            }

            gate.want()
            advanceUntilIdle()

            settlementQueued shouldBe true
            cleared shouldBe false
            store.transcript shouldBe listOf("enter:true", "wrote:true")
            store.wanted shouldBe true
        }

    @Test
    fun `want returns the token it minted, not one a later connect minted`() =
        runTest {
            // `TunnelService.connectFromCommand` hands this value to
            // `startTunnel` as the new session's owner. Were it read after the
            // mutex is released — `withLock { … }; return token.get()` — a
            // second connect let in by that release could already have moved
            // it, and the first session would record the second's token.
            // Same Unconfined mechanism as the previous test: the second
            // connect resumes inline inside the first `want()`'s unlock.
            val store = RecordingIntent()
            val gate = SessionIntentGate(store::write)

            var second: Int? = null
            var secondQueued = false
            store.beforeWrite = { value ->
                if (value && !secondQueued) {
                    secondQueued = true
                    launch(Dispatchers.Unconfined) { second = gate.want() }
                }
            }

            val first = gate.want()
            advanceUntilIdle()

            secondQueued shouldBe true
            first shouldBe 1
            second shouldBe 2
            gate.currentToken() shouldBe 2
        }
}
