// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.isDirectiveEnabled
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

internal const val REFRESH_WORK_NAME = "subscription-refresh"

/** Used when a provider sends no `profile-update-interval` and nothing is pinned. */
internal const val DEFAULT_INTERVAL_HOURS = 12

/**
 * `profile-update-interval`'s own provider-path bounds (`DirectiveRegistry`'s
 * `DirectiveKind.Integer(1, 8760)` — 1 hour to 1 year). Duplicated here, not
 * shared, because `:app` cannot depend on `:core:parser` for one constant
 * (ARCHITECTURE.md §4) and this is the *pin* path's backstop, not the
 * provider path's — see [resolveIntervalHours].
 */
internal const val MIN_INTERVAL_HOURS = 1
internal const val MAX_INTERVAL_HOURS = 8_760

private const val TAG = "RefreshScheduler"
private const val HOUR_MILLIS = 3_600_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val KEY_INTERVAL = "profile-update-interval"
private const val KEY_AUTO_UPDATE = "subscription-auto-update-enable"
private const val KEY_AUTO_UPDATE_OPEN = "subscription-auto-update-open-enable"

/**
 * If [reschedule] can't even compute what's due (e.g. a Room read failure), retry this soon
 * rather than leave the one pending job unscheduled forever — well inside the shortest real
 * interval (1 h, [MIN_INTERVAL_HOURS]), so a transient failure heals itself quickly.
 */
private const val FALLBACK_RETRY_MILLIS = 15 * MILLIS_PER_MINUTE

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
 * Resolves a stored `profile-update-interval` value to a safe hour count.
 *
 * The provider path is already bounded — `DirectiveValidator` rejects (not clamps) anything
 * outside `[1, 8760]` before it is ever stored. This function's real job is the *pin* path:
 * `SubscriptionRepository.pin(id, key, value)` accepts an arbitrary `String` with no bounds
 * checking, and a user (Task 15) can pin `profile-update-interval` to anything. Clamping here,
 * once, is cheaper and more reliable than validating every future caller of `pin()`.
 */
internal fun resolveIntervalHours(rawValue: String?): Int =
    rawValue?.toIntOrNull()?.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS) ?: DEFAULT_INTERVAL_HOURS

/**
 * The next refresh deadline for one subscription. [lastAttemptedAt] deliberately includes a
 * failure or an empty response, so those outcomes wait for their normal provider interval rather
 * than immediately rebuilding a one-shot work chain. [lastFetchedAt] is intentionally absent:
 * it remains the last server-bearing success for UI reporting.
 */
internal fun nextAttemptDueAt(
    now: Long,
    lastAttemptedAt: Long?,
    intervalHours: Int,
): Long = lastAttemptedAt?.plus(intervalHours * HOUR_MILLIS) ?: now

/** Turns one stored subscription row into the scheduler input used by [dueChecks]. */
internal fun dueCheckFor(
    subscription: StoredSubscription,
    now: Long,
    intervalHours: Int,
): DueCheck =
    DueCheck(
        subscriptionId = subscription.id,
        dueAtEpochMillis = nextAttemptDueAt(now, subscription.lastAttemptedAt, intervalHours),
    )

/** The general `subscription-auto-update-enable` gate under §A.1's boolean rule. */
internal fun scheduledAutoUpdateEnabled(value: String?): Boolean = isDirectiveEnabled(value)

/** The launch-only `subscription-auto-update-open-enable` gate under §A.1's boolean rule. */
internal fun openAutoUpdateEnabled(value: String?): Boolean = isDirectiveEnabled(value)

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
    /**
     * Recomputes the earliest due time and replaces the pending job.
     *
     * Runs entirely under [NonCancellable]. `SubscriptionRefreshWorker.doWork()` and
     * `SubspaceApplication`'s launch-time call both invoke this from a `finally` specifically so a
     * crash or cancellation mid-[refreshDue] still reschedules — but WorkManager cancels a
     * `CoroutineWorker`'s `Job` outright once it exceeds its ~10-minute execution ceiling (a real
     * risk here: [refreshDue] syncs due subscriptions sequentially), and without this, the very
     * first suspend call inside [dueChecks] — Room's `Flow.first()` — would throw
     * [CancellationException] before `enqueueUniqueWork`/`cancelUniqueWork` is ever reached. The
     * `finally` would still run, but `reschedule()` itself would silently do nothing: exactly the
     * "chain goes dead with no future wakeup" failure this class exists to prevent. [NonCancellable]
     * makes the whole body — the suspend reads *and* the enqueue call — run to completion
     * regardless of the caller's cancellation state.
     */
    suspend fun reschedule() {
        withContext(NonCancellable) {
            val now = System.currentTimeMillis()
            val next = nextDueAtOrFallback(now)

            if (next == null) {
                workManager.cancelUniqueWork(REFRESH_WORK_NAME)
            } else {
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
        }
    }

    /**
     * Keeps the pending work aligned with Room mutations that can move the earliest due time.
     *
     * The app, not `:feature:profiles`, owns this collector: feature modules must not depend on
     * `:app` (§4), and collecting the data-layer signal here makes add/delete/pin/provider-refresh
     * changes converge on the same [reschedule] path as worker completion and app launch.
     */
    suspend fun rescheduleOnChanges() {
        subscriptions.observeRefreshScheduleChanges().collect { reschedule() }
    }

    /**
     * Syncs every subscription that is due now. Called by the worker (interval trigger) and at
     * launch (spec §8's "on launch" trigger).
     *
     * @param onOpen when true, also requires `subscription-auto-update-open-enable` — the provider
     *   directive that specifically governs refresh-on-app-open, distinct from
     *   `subscription-auto-update-enable`'s general kill switch which [dueChecks] already applies to
     *   both triggers. A user pin on either key overrides the provider (spec §8), the same
     *   precedence [dueChecks] already gives the general switch.
     */
    suspend fun refreshDue(onOpen: Boolean = false) {
        val now = System.currentTimeMillis()
        dueChecks(now)
            .filter { it.dueAtEpochMillis <= now }
            .filter { !onOpen || openRefreshEnabled(it.subscriptionId) }
            .forEach { syncer.sync(it.subscriptionId) }
    }

    private suspend fun openRefreshEnabled(id: Long): Boolean =
        openAutoUpdateEnabled(
            subscriptions.effective(id, KEY_AUTO_UPDATE_OPEN, default = "true").value,
        )

    /**
     * [nextDueAt] over [dueChecks], with a bounded fallback if [dueChecks] itself throws — a dead
     * refresh chain is silent and unrecoverable short of a reinstall, so computing "what's due"
     * failing is not allowed to mean "nothing gets scheduled." [CancellationException] is rethrown
     * rather than treated as a computation failure; it should not occur here since the caller already
     * runs this under [NonCancellable], but if it ever did, silently converting real cancellation into
     * a "retry soon" would be the wrong response to it.
     */
    @Suppress("TooGenericExceptionCaught") // deliberate backstop — see KDoc above and reschedule()'s.
    private suspend fun nextDueAtOrFallback(now: Long): Long? =
        try {
            nextDueAt(now, dueChecks(now))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Exception) {
            val retryMinutes = FALLBACK_RETRY_MILLIS / MILLIS_PER_MINUTE
            Log.w(TAG, "could not compute due subscriptions; retrying in $retryMinutes min")
            now + FALLBACK_RETRY_MILLIS
        }

    private suspend fun dueChecks(now: Long): List<DueCheck> =
        subscriptions.observeSubscriptions().first().mapNotNull { subscription ->
            // A provider may disable auto-update entirely (spec §8). A user pin
            // overrides that — a provider cannot stop a user who pinned it on.
            val enabled =
                scheduledAutoUpdateEnabled(
                    subscriptions.effective(subscription.id, KEY_AUTO_UPDATE, default = "true").value,
                )
            if (!enabled) return@mapNotNull null

            val intervalDefault = DEFAULT_INTERVAL_HOURS.toString()
            val intervalValue = subscriptions.effective(subscription.id, KEY_INTERVAL, intervalDefault).value
            val hours = resolveIntervalHours(intervalValue)

            dueCheckFor(subscription, now, hours)
        }
}
