// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.failure
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers [TerminalOutcome], the seam extracted for PR #4 review P1 finding A.
 *
 * The bug was ordering, not logic. Both terminal paths in [TunnelService] published their
 * outcome, suspended to persist it, and *then* mutated service lifecycle state — so a
 * teardown or a newer start that ran during the suspension left a stale coroutine to resume
 * and reassert lifecycle it no longer owned. The connected path could restore the connected
 * foreground notification after teardown had removed it (§5.5's lying UI). The failed path
 * could remove a *newer* connection's foreground state, or `stopSelf()` its service.
 *
 * That is not reachable through `TunnelService` in a JVM test — it is a `VpnService`, and
 * this project carries no Robolectric or mocking library (§10.7 does not justify one for
 * this). So the ordering rule lives in this small class with the lifecycle mutations passed
 * in as lambdas, and these tests drive it with fakes: Android and native tunnel behaviour
 * stay in `TunnelService`.
 *
 * The invariant under test: **once an outcome has been published, persistence must never be
 * followed by a lifecycle mutation from that generation.**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalOutcomeTest {
    /**
     * Stands in for the service's foreground/notification state and its own liveness.
     *
     * Records a transcript rather than just a final value: this bug is about *ordering*, and
     * an assertion on end state alone would pass for a sequence that briefly did the wrong
     * thing on the way there.
     */
    private class FakeLifecycle {
        val transcript = mutableListOf<String>()
        var foregroundText: String? = null
            private set
        var stopped = false
            private set

        fun goForeground(text: String) {
            foregroundText = text
            transcript += "foreground:$text"
        }

        fun removeForeground() {
            foregroundText = null
            transcript += "foreground:none"
        }

        fun stopSelf() {
            stopped = true
            transcript += "stopSelf"
        }
    }

    /** The service's `generation` and `publishLocked`, which [TerminalOutcome] is given access to. */
    private class FakeService {
        val lock = Any()
        var generation = 0
        val published = mutableListOf<ConnectionState>()
        val lifecycle = FakeLifecycle()

        val outcome =
            TerminalOutcome(
                lock = lock,
                currentGeneration = { generation },
                publish = { state ->
                    published += state
                    lifecycle.transcript += "publish:" + state::class.simpleName
                },
            )

        /** What `stopTunnel` does to the fields these tests care about. */
        fun tearDown() {
            synchronized(lock) { ++generation }
            lifecycle.removeForeground()
            published += ConnectionState.Disconnected
            lifecycle.transcript += "publish:Disconnected"
        }

        /** What a newer `startTunnel` does before its own slow work. */
        fun startNewGeneration(): Int {
            val gen = synchronized(lock) { ++generation }
            lifecycle.goForeground("connecting")
            return gen
        }
    }

    private val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808)
    private val failed = failure(FailureReason.CoreStartFailed, "redacted")

    @Test
    fun `the lifecycle mutation and the publication happen as one ordered transition`() =
        runTest {
            val service = FakeService()
            val gen = service.startNewGeneration()

            val settled =
                service.outcome.settle(
                    gen = gen,
                    state = connected,
                    lifecycle = { service.lifecycle.goForeground("connected") },
                    persist = { service.lifecycle.transcript += "persist" },
                )

            settled shouldBe true
            // Lifecycle before publication, and persistence strictly after both.
            service.lifecycle.transcript shouldBe
                listOf("foreground:connecting", "foreground:connected", "publish:Connected", "persist")
        }

    @Test
    fun `a superseded generation mutates nothing and publishes nothing`() =
        runTest {
            val service = FakeService()
            val gen = service.startNewGeneration()
            service.tearDown()
            val transcriptAfterTeardown = service.lifecycle.transcript.toList()

            val settled =
                service.outcome.settle(
                    gen = gen,
                    state = connected,
                    lifecycle = { service.lifecycle.goForeground("connected") },
                    persist = { service.lifecycle.transcript += "persist" },
                )

            settled shouldBe false
            // Not even the persist ran: there is no outcome to record for a
            // generation that no longer owns the tunnel.
            service.lifecycle.transcript shouldBe transcriptAfterTeardown
        }

    @Test
    fun `a delayed successful write cannot reassert foreground state after teardown`() =
        runTest {
            // The exact race in the report: teardown runs while the connected
            // outcome is still being persisted.
            val service = FakeService()
            val gen = service.startNewGeneration()
            val writeStarted = CompletableDeferred<Unit>()
            val letWriteFinish = CompletableDeferred<Unit>()

            val start =
                launch {
                    service.outcome.settle(
                        gen = gen,
                        state = connected,
                        lifecycle = { service.lifecycle.goForeground("connected") },
                        persist = {
                            writeStarted.complete(Unit)
                            letWriteFinish.await()
                            service.lifecycle.transcript += "persist"
                        },
                    )
                }

            writeStarted.await()
            service.tearDown()
            letWriteFinish.complete(Unit)
            start.join()

            // The connected notification must not come back. Before the fix, the
            // stale coroutine called goForeground() here, after teardown.
            service.lifecycle.foregroundText shouldBe null
            service.lifecycle.transcript.last() shouldBe "persist"
            service.published.last() shouldBe ConnectionState.Disconnected
        }

    @Test
    fun `a delayed failed write cannot stop or alter a newer generation`() =
        runTest {
            val service = FakeService()
            val failingGen = service.startNewGeneration()
            val writeStarted = CompletableDeferred<Unit>()
            val letWriteFinish = CompletableDeferred<Unit>()

            val failingStart =
                launch {
                    service.outcome.settle(
                        gen = failingGen,
                        state = failed,
                        lifecycle = {
                            service.lifecycle.removeForeground()
                            service.lifecycle.stopSelf()
                        },
                        persist = {
                            writeStarted.complete(Unit)
                            letWriteFinish.await()
                            service.lifecycle.transcript += "persist"
                        },
                    )
                }

            writeStarted.await()
            // A newer connection begins while the failure is still being written.
            service.startNewGeneration()
            val stoppedBeforeWriteFinished = service.lifecycle.stopped
            letWriteFinish.complete(Unit)
            failingStart.join()

            // The newer generation keeps its foreground state, and the old
            // coroutine's resume adds no stopSelf of its own.
            service.lifecycle.foregroundText shouldBe "connecting"
            service.lifecycle.stopped shouldBe stoppedBeforeWriteFinished
            service.lifecycle.transcript.count { it == "stopSelf" } shouldBe 1
            service.lifecycle.transcript.last() shouldBe "persist"
        }

    @Test
    fun `persistence cannot run before the transition it records`() =
        runTest {
            // Guards the ordering from the other side: a future edit that moved the
            // write ahead of the transition would persist an outcome that had not
            // been committed, and could persist one for a superseded generation.
            val service = FakeService()
            val gen = service.startNewGeneration()
            var stateAtWriteTime: String? = null

            service.outcome.settle(
                gen = gen,
                state = connected,
                lifecycle = { service.lifecycle.goForeground("connected") },
                persist = { stateAtWriteTime = service.lifecycle.foregroundText },
            )

            stateAtWriteTime shouldBe "connected"
        }
}
