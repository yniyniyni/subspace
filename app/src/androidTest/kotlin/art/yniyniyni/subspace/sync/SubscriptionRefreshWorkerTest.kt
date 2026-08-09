// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import art.yniyniyni.subspace.core.data.testing.InMemorySubscriptionStack
import io.kotest.matchers.shouldBe
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
}
