// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * Two properties under test:
 *
 *  1. **Ownership** — a clear whose token has been superseded writes nothing.
 *  2. **Serialisation** — a connect's write cannot land between the ownership
 *     check and the clear.
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
}
