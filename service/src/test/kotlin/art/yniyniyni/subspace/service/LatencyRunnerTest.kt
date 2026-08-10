// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.LatencyOptions
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.PingMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * `LatencyRunner` is generic in the profile type precisely so this — the
 * scheduling, fencing and cancellation logic — can be exercised without Room or
 * libXray. `TunnelService` binds it to `StoredProfile`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatencyRunnerTest {
    private val options = LatencyOptions(PingMode.TCP, timeoutSeconds = 1, checkUrl = "https://x.invalid")

    @Test
    fun `every requested profile produces exactly one result, then finished`() =
        runTest {
            val results = mutableListOf<Long>()
            var finishedRunId: Long? = null
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ -> LatencyResult.ok(10) },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                )

            runner.start(
                runId = 1L,
                profileIds = longArrayOf(1, 2, 3),
                options = options,
                onResult = { _, id, _ -> results += id },
                onFinished = { runId -> finishedRunId = runId },
            )
            advanceUntilIdle()

            results.sorted() shouldContainExactly listOf(1L, 2L, 3L)
            finishedRunId shouldBe 1L
        }

    @Test
    fun `a profile that no longer exists reports UNREACHABLE rather than being skipped`() =
        runTest {
            val results = mutableListOf<LatencyResult>()
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ -> LatencyResult.ok(10) },
                    // Deleted between the list rendering and the run reaching it.
                    loadProfile = { null },
                    scope = TestScope(testScheduler),
                )

            runner.start(1L, longArrayOf(7), options, { _, _, r -> results += r }, {})
            advanceUntilIdle()

            // §10.4: a real outcome, not a silent skip — the row must stop saying
            // "testing" even when its profile vanished.
            results.single().outcome shouldBe LatencyOutcome.UNREACHABLE
        }

    @Test
    fun `no more than the configured number of measurements run at once`() =
        runTest {
            val inFlight = AtomicInteger(0)
            val peak = AtomicInteger(0)
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ ->
                        val now = inFlight.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, now) }
                        yield()
                        inFlight.decrementAndGet()
                        LatencyResult.ok(1)
                    },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                    concurrency = 2,
                )

            runner.start(1L, (1L..10L).toList().toLongArray(), options, { _, _, _ -> }, {})
            advanceUntilIdle()

            peak.get() shouldBeLessThanOrEqual 2
        }

    @Test
    fun `starting a second run supersedes the first and stops its scheduling`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val seen = mutableListOf<Long>()
            val runner =
                LatencyRunner<Long>(
                    measure = { id, _ ->
                        seen += id
                        if (id == 1L) gate.await()
                        LatencyResult.ok(1)
                    },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                    concurrency = 1,
                )

            runner.start(1L, longArrayOf(1, 2, 3), options, { _, _, _ -> }, {})
            advanceUntilIdle()
            runner.start(2L, longArrayOf(9), options, { _, _, _ -> }, {})
            gate.complete(Unit)
            advanceUntilIdle()

            // 2 and 3 were never scheduled: the first run was superseded while 1
            // still held the single permit.
            seen shouldContainExactly listOf(1L, 9L)
        }

    @Test
    fun `results from a superseded run are not delivered`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val delivered = mutableListOf<Long>()
            val runner =
                LatencyRunner<Long>(
                    measure = { id, _ ->
                        if (id == 1L) gate.await()
                        LatencyResult.ok(1)
                    },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                )

            runner.start(1L, longArrayOf(1), options, { _, id, _ -> delivered += id }, {})
            advanceUntilIdle()
            runner.start(2L, longArrayOf(9), options, { _, id, _ -> delivered += id }, {})
            gate.complete(Unit)
            advanceUntilIdle()

            // The superseded measurement finished — cancellation cannot interrupt
            // a blocking native call — but its result must not land on a row the
            // new run is retesting.
            delivered shouldContainExactly listOf(9L)
        }

    @Test
    fun `cancelling a run stops it delivering and finishing`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val delivered = mutableListOf<Long>()
            var finished = false
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ ->
                        gate.await()
                        LatencyResult.ok(1)
                    },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                )

            runner.start(1L, longArrayOf(1), options, { _, id, _ -> delivered += id }, { finished = true })
            advanceUntilIdle()
            runner.cancel(1L)
            gate.complete(Unit)
            advanceUntilIdle()

            delivered shouldContainExactly emptyList()
            finished shouldBe false
        }

    @Test
    fun `cancelling a run that is no longer active does not stop the current one`() =
        runTest {
            val delivered = mutableListOf<Long>()
            val runner =
                LatencyRunner<Long>(
                    measure = { _, _ -> LatencyResult.ok(1) },
                    loadProfile = { id -> id },
                    scope = TestScope(testScheduler),
                )

            runner.start(2L, longArrayOf(9), options, { _, id, _ -> delivered += id }, {})
            // A stale cancel arriving late from a run that already ended.
            runner.cancel(1L)
            advanceUntilIdle()

            delivered shouldContainExactly listOf(9L)
        }
}
