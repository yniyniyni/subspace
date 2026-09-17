// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.failure

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

    /**
     * Stands in for `backoffJob` plus which generation armed it.
     *
     * [TunnelService.cancelBackoffRetryLocked] has no idea which generation armed the job it
     * cancels — that is exactly the defect this fake exists to expose: an unconditional
     * `cancel()` called from the wrong generation's cleanup wipes out whichever generation is
     * currently armed, not the caller's own.
     */
    private class FakeRetry {
        var armedByGen: Int? = null
            private set

        fun arm(gen: Int) {
            armedByGen = gen
        }

        fun cancel() {
            armedByGen = null
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
                    lifecycle = {
                        service.lifecycle.goForeground("connected")
                        true
                    },
                    persist = { service.lifecycle.transcript += "persist" },
                )

            settled shouldBe TerminalSettlement.Committed
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
                    lifecycle = {
                        service.lifecycle.goForeground("connected")
                        true
                    },
                    persist = { service.lifecycle.transcript += "persist" },
                )

            settled shouldBe TerminalSettlement.Superseded
            // Not even the persist ran: there is no outcome to record for a
            // generation that no longer owns the tunnel.
            service.lifecycle.transcript shouldBe transcriptAfterTeardown
        }

    @Test
    fun `a rejected lifecycle publishes and persists nothing`() =
        runTest {
            val service = FakeService()
            val gen = service.startNewGeneration()
            var persisted = false

            val settled =
                service.outcome.settleHandlingLifecycleRejection(
                    gen = gen,
                    state = connected,
                    lifecycle = { false },
                    persist = { persisted = true },
                    onLifecycleRejected = { service.lifecycle.transcript += "cleanup" },
                )

            settled shouldBe TerminalSettlement.LifecycleRejected
            service.published shouldBe emptyList()
            persisted shouldBe false
            service.lifecycle.transcript shouldBe listOf("foreground:connecting", "cleanup")
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
                        lifecycle = {
                            service.lifecycle.goForeground("connected")
                            true
                        },
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
                            true
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
                lifecycle = {
                    service.lifecycle.goForeground("connected")
                    true
                },
                persist = { stateAtWriteTime = service.lifecycle.foregroundText },
            )

            stateAtWriteTime shouldBe "connected"
        }

    /**
     * Pins the P1 fix: `attachTun`/`attachRetainedTun` used to cancel the pending backoff
     * retry and reset the attempt counter inside `persist`, after the suspending
     * `connectionRecorder.record` write. `persist` runs only once `settle` has committed, but
     * committed and *still current* are different properties — a newer generation can arm its
     * own retry while an older generation's `persist` is still suspended, and an unconditional
     * cancel issued once that suspension resumes cancels the newer generation's timer instead
     * of the (nonexistent, by then) older one.
     *
     * The fix moves the cancel into `lifecycle`, gated on `established`, so it runs under
     * `settle`'s lock as part of the generation-checked transition — before `persist` ever
     * suspends, and unreachable by a superseded generation at all. This test drives `lifecycle`
     * and `persist` shaped exactly that way and asserts a newer generation's retry survives.
     */
    @Test
    fun `a retry armed by a newer generation survives an older generation's settlement`() =
        runTest {
            val service = FakeService()
            val retry = FakeRetry()
            val oldGen = service.startNewGeneration()
            val writeStarted = CompletableDeferred<Unit>()
            val letWriteFinish = CompletableDeferred<Unit>()

            // Stands in for a retry armed by a prior failed attempt of `oldGen` itself —
            // the successful connect below is what should clear it.
            retry.arm(oldGen)

            val oldSettle =
                launch {
                    service.outcome.settle(
                        gen = oldGen,
                        state = connected,
                        lifecycle = {
                            service.lifecycle.goForeground("connected")
                            // The fixed shape: cancel while [lock] is still held, before
                            // `persist` gets anywhere near suspending.
                            retry.cancel()
                            true
                        },
                        persist = {
                            writeStarted.complete(Unit)
                            letWriteFinish.await()
                        },
                    )
                }

            writeStarted.await()
            // A newer generation starts — e.g. `restartCoreRetainingTun` on a network
            // change — fails retryably, and arms its own timer, all while `oldGen`'s
            // `persist` is still suspended in the write.
            val newGen = service.startNewGeneration()
            retry.arm(newGen)

            letWriteFinish.complete(Unit)
            oldSettle.join()

            // `oldGen`'s cancel already ran, under lock, before `persist` ever suspended —
            // it cannot reach `newGen`'s timer no matter how long the write takes.
            retry.armedByGen shouldBe newGen
        }

    /**
     * The other half of the fix's contract: cancellation only when [lifecycle] actually
     * establishes the transition. A rejected foreground hands off to
     * [TunnelService.handleForegroundLifecycleRejection] instead of committing, and must not
     * discard a retry that belongs to a session which never came up.
     */
    @Test
    fun `a rejected lifecycle establishment leaves a pending retry untouched`() =
        runTest {
            val service = FakeService()
            val retry = FakeRetry()
            val gen = service.startNewGeneration()
            retry.arm(gen)

            val settled =
                service.outcome.settleHandlingLifecycleRejection(
                    gen = gen,
                    state = connected,
                    lifecycle = {
                        val established = false // stands in for goForeground() refusing
                        if (established) {
                            retry.cancel()
                        }
                        established
                    },
                    persist = { service.lifecycle.transcript += "persist" },
                    onLifecycleRejected = { service.lifecycle.transcript += "cleanup" },
                )

            settled shouldBe TerminalSettlement.LifecycleRejected
            retry.armedByGen shouldBe gen
        }
}
