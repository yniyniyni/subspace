// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import art.yniyniyni.subspace.core.data.testing.InMemorySubscriptionStack
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Keep this test narrow on purpose: it proves the worker runs and reschedules
 * without throwing. The scheduling behaviour that actually matters — that a
 * second refresh follows the first with the app backgrounded — cannot be
 * proven by `work-testing`'s driven clock and is a device-checklist line
 * (Task 18 Step 3).
 *
 * Building a [SubscriptionRefreshWorker] needs a [WorkerFactory] because it is
 * `@HiltWorker`. Rather than pull in the whole Hilt test runner for this one
 * test, a plain [WorkerFactory] is built by hand below, and the
 * [SubscriptionRepository][art.yniyniyni.subspace.core.data.SubscriptionRepository]
 * / [SubscriptionSyncer][art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer]
 * pair [RefreshScheduler] needs comes from [InMemorySubscriptionStack] — a
 * `:core:data` `testFixtures` helper, because those two classes' constructors
 * are `internal` to that module (DI-only construction) and this test cannot
 * reach `:core:network` itself to build one by hand (ARCHITECTURE.md §4: only
 * `:core:data` may depend on it).
 */
class SubscriptionRefreshWorkerTest {
    private lateinit var context: Context
    private lateinit var stack: InMemorySubscriptionStack

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        stack = InMemorySubscriptionStack(context)
    }

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun theScheduledRefreshWaitsForANetwork() =
        runTest {
            // M4's device run: the work carried required_network_type = NOT_REQUIRED, so it fired
            // while the device was offline, both fetches recorded Unreachable, and — because
            // lastAttemptedAt advances on failure by design — each subscription then waited a full
            // interval before retrying. At the 12h default that is half a day of staleness bought
            // by one bad moment. Deferring on the constraint is what WorkManager is for.
            stack.repository.add("https://example.com/sub", name = "Constrained")
            val scheduler = RefreshScheduler(WorkManager.getInstance(context), stack.repository, stack.syncer)

            scheduler.reschedule()

            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(REFRESH_WORK_NAME).get()
            work.single().constraints.requiredNetworkType shouldBe NetworkType.CONNECTED
        }

    @Test
    fun theWorkerSucceedsAndReschedulesWithNoSubscriptions() =
        runTest {
            val scheduler =
                RefreshScheduler(
                    WorkManager.getInstance(context),
                    stack.repository,
                    stack.syncer,
                )
            val workerFactory =
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker = SubscriptionRefreshWorker(appContext, workerParameters, scheduler)
                }
            val worker =
                TestListenableWorkerBuilder<SubscriptionRefreshWorker>(context)
                    .setWorkerFactory(workerFactory)
                    .build()

            worker.doWork() shouldBe ListenableWorker.Result.success()
        }

    /**
     * Review round 1, Important 1: `reschedule()`'s own suspend calls (Room's `Flow.first()` inside
     * `dueChecks()`) used to throw [kotlinx.coroutines.CancellationException] the moment the calling
     * `Job` was cancelled — exactly what WorkManager does to a `CoroutineWorker` that outruns its
     * ~10-minute ceiling. That left `reschedule()`'s `finally` running but never reaching
     * `enqueueUniqueWork`: the one pending job silently vanished. `RefreshScheduler.reschedule()` now
     * runs under `NonCancellable`; this proves it by cancelling the job while `refreshDue()` is
     * genuinely mid-flight (blocked inside a fetch, not just cancelled before it started) and
     * asserting a future wakeup is still enqueued afterwards.
     */
    @Test
    fun cancellingTheJobMidRefreshDueStillEnqueuesAFutureWakeup() =
        runTest {
            val fetchStarted = CompletableDeferred<Unit>()
            val hangingStack =
                InMemorySubscriptionStack(
                    context,
                    onFetch = {
                        fetchStarted.complete(Unit)
                        delay(Long.MAX_VALUE)
                    },
                )
            try {
                hangingStack.repository.add("https://example.com/sub", name = "Cancel me mid-fetch")
                val scheduler =
                    RefreshScheduler(
                        WorkManager.getInstance(context),
                        hangingStack.repository,
                        hangingStack.syncer,
                    )

                val job =
                    launch {
                        try {
                            scheduler.refreshDue()
                        } finally {
                            scheduler.reschedule()
                        }
                    }

                // Guarantees refreshDue() actually reached the fetch — not just "cancelled before it
                // ever ran anything."
                fetchStarted.await()
                job.cancelAndJoin()

                // Whether that enqueued work then runs successfully is a different question this test
                // doesn't care about — this WorkManager instance has no Hilt-capable WorkerFactory
                // (initializeTestWorkManager's default config can't construct a @HiltWorker), so the
                // test WorkManager's own executor picks it straight up and it terminates on its own
                // (state FAILED, for a reason unrelated to what's under test here). What matters is
                // that enqueueUniqueWork was reached at all: before the NonCancellable fix, this list
                // was empty because reschedule() never got that far.
                val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(REFRESH_WORK_NAME).get()
                workInfos.isNotEmpty() shouldBe true
            } finally {
                hangingStack.close()
            }
        }

    /**
     * Review round 1, Important 2: `subscription-auto-update-open-enable` is spec §8's directive for
     * the on-launch trigger specifically, distinct from `subscription-auto-update-enable`'s general
     * kill switch. `refreshDue(onOpen = true)` — what `SubspaceApplication` calls at launch — must
     * skip a subscription that opted out of refresh-on-open, while the interval path
     * (`refreshDue()`/`refreshDue(onOpen = false)`, what the worker calls) is unaffected by that key.
     *
     * "Did a sync run?" is read off `lastAttemptedAt`, not `lastFetchedAt`. [InMemorySubscriptionStack]'s
     * stub source returns an empty, header-less body, so every sync here lands on the syncer's
     * `NoServers` branch — and `recordFetchResult` only advances `lastFetchedAt` when the status is
     * null (a server-bearing success), while `lastAttemptedAt` advances on every attempt. Asserting on
     * `lastFetchedAt` would make the skip assertion vacuously true: it stays null whether or not the
     * open-refresh gate works at all.
     */
    @Test
    fun aSubscriptionThatOptsOutOfOpenRefreshIsSkippedOnlyOnTheOpenTrigger() =
        runTest {
            val id = stack.repository.add("https://example.com/sub", name = "Open-disabled")
            stack.repository.pin(id, "subscription-auto-update-open-enable", "false")
            val scheduler = RefreshScheduler(WorkManager.getInstance(context), stack.repository, stack.syncer)

            scheduler.refreshDue(onOpen = true)
            stack.repository.observeSubscriptions().first().single { it.id == id }.lastAttemptedAt shouldBe null

            scheduler.refreshDue(onOpen = false)
            stack.repository.observeSubscriptions().first().single { it.id == id }.lastAttemptedAt shouldNotBe null
        }
}
