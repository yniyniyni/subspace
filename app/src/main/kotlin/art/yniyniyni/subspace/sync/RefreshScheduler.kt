// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

internal const val REFRESH_WORK_NAME = "subscription-refresh"

/** Used when a provider sends no `profile-update-interval` and nothing is pinned. */
internal const val DEFAULT_INTERVAL_HOURS = 12

private const val HOUR_MILLIS = 3_600_000L
private const val KEY_INTERVAL = "profile-update-interval"
private const val KEY_AUTO_UPDATE = "subscription-auto-update-enable"

/**
 * Provides the singleton [WorkManager] instance [RefreshScheduler] injects.
 *
 * [WorkManager] itself has no `@Inject` constructor — it is obtained from [WorkManager.getInstance]
 * — so Hilt needs an explicit binding rather than constructor injection, unlike every other class in
 * this file.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object WorkManagerModule {
    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}

/** When one subscription next wants refreshing. */
internal data class DueCheck(val subscriptionId: Long, val dueAtEpochMillis: Long)

/**
 * The earliest moment any subscription is due, or null when none are scheduled.
 *
 * Never returns a past time: an overdue subscription schedules for [now], not
 * for a negative delay.
 */
internal fun nextDueAt(
    now: Long,
    subscriptions: List<DueCheck>,
): Long? = subscriptions.minOfOrNull { max(now, it.dueAtEpochMillis) }

/**
 * Keeps exactly one pending refresh job aimed at the earliest due subscription.
 *
 * Spec §8: a `PeriodicWorkRequest` cannot express N subscriptions with N
 * intervals — it has one period, so it would tick at the shortest of them and
 * mostly find nothing due. A self-rescheduling one-shot wakes when something
 * actually is.
 *
 * Re-scheduling is triggered by a run finishing, and by anything that can move
 * the earliest due time: adding or deleting a subscription, a fetch returning a
 * changed `profile-update-interval`, or the user pinning one.
 */
@Singleton
internal class RefreshScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val subscriptions: SubscriptionRepository,
    private val syncer: SubscriptionSyncer,
) {
    /** Recomputes the earliest due time and replaces the pending job. */
    suspend fun reschedule() {
        val now = System.currentTimeMillis()
        val due = dueChecks(now)
        val next = nextDueAt(now, due)

        if (next == null) {
            workManager.cancelUniqueWork(REFRESH_WORK_NAME)
            return
        }

        workManager.enqueueUniqueWork(
            REFRESH_WORK_NAME,
            // REPLACE, not KEEP: the whole point of rescheduling is that the
            // earliest due time moved. KEEP would leave a job aimed at a time
            // that is no longer the earliest.
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SubscriptionRefreshWorker>()
                .setInitialDelay(next - now, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** Syncs every subscription that is due now. Called by the worker and at launch. */
    suspend fun refreshDue() {
        val now = System.currentTimeMillis()
        dueChecks(now)
            .filter { it.dueAtEpochMillis <= now }
            .forEach { syncer.sync(it.subscriptionId) }
    }

    private suspend fun dueChecks(now: Long): List<DueCheck> =
        subscriptions.observeSubscriptions().first().mapNotNull { subscription ->
            // A provider may disable auto-update entirely (spec §8). A user pin
            // overrides that — a provider cannot stop a user who pinned it on.
            val enabled = subscriptions
                .effective(subscription.id, KEY_AUTO_UPDATE, default = "true")
                .value != "false"
            if (!enabled) return@mapNotNull null

            val hours = subscriptions
                .effective(subscription.id, KEY_INTERVAL, default = DEFAULT_INTERVAL_HOURS.toString())
                .value?.toIntOrNull() ?: DEFAULT_INTERVAL_HOURS

            DueCheck(
                subscriptionId = subscription.id,
                // Never fetched yet: due immediately.
                dueAtEpochMillis = subscription.lastFetchedAt?.plus(hours * HOUR_MILLIS) ?: now,
            )
        }
}
