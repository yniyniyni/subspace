// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.isDirectiveEnabled
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncFailure
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

/** The floor, and the whole cost, of a transient failure that clears immediately. */
private const val MIN_RETRY_MILLIS = 15 * MILLIS_PER_MINUTE

/** Retry delay is this fraction of how overdue a subscription already is — see [retryDelayMillis]. */
private const val BACKOFF_DIVISOR = 4

/**
 * How recently a subscription must have been attempted for the on-open trigger to skip it.
 *
 * The interval gate does not apply on open (spec §8's trigger is "the app opened", not "the
 * interval elapsed"), so this is the only thing standing between the user and a fetch per
 * app-switch. Small enough that opening the app after breakfast refreshes, large enough that
 * bouncing to the browser and back does not.
 */
internal const val OPEN_REFRESH_MIN_GAP_MILLIS = 5 * MILLIS_PER_MINUTE

/**
 * The same floor after a failed attempt. Short, because reopening the app is the user's way of
 * saying "try again" and the previous attempt fetched nothing — but not absent, so a provider that
 * is genuinely down is not asked again on every single app switch.
 */
internal const val OPEN_REFRESH_RETRY_GAP_MILLIS = MILLIS_PER_MINUTE

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
 * Whether [status] describes a condition the very next attempt could plausibly find gone.
 *
 * The distinction earns its keep because the two halves want opposite pacing. A wrong URL, a
 * subscription the provider has revoked, or a device at its HWID cap will answer identically in
 * fifteen minutes and in twelve hours, so retrying them quickly is pure noise against the
 * provider. A reset handshake or a 503 is weather.
 *
 * M4's device run is the evidence. One subscription went `TlsFailure` → `Unreachable` → success
 * across eighteen minutes on one unchanged host, while a second subscription on the same link
 * never faltered; the failure had cleared itself long before the next attempt was allowed. Pacing
 * that outcome at a full provider interval cost ten hours of staleness for a fault that lasted
 * minutes.
 *
 * A status this cannot parse — `NoServers`, or anything a future version writes — is treated as
 * permanent on purpose: the conservative direction is the one that does not hammer a provider.
 *
 * Matching through [SubscriptionSyncFailure] rather than string literals is **not** a compile-time
 * guard, and an earlier version of this comment wrongly claimed it was. The persisted string is
 * `FetchFailure.name`, and the two enums are bridged by an exhaustive `when`; renaming a
 * `FetchFailure` member still compiles, because that `when` simply maps the new name onto the
 * unchanged `SubscriptionSyncFailure` member. What would actually happen is silent: the stored
 * string stops parsing, `runCatching` yields null, and every network failure is reclassified as
 * permanent — back to a full interval of staleness, with nothing failing to announce it. The
 * `runCatching` that makes this robust against unknown strings is exactly what makes that quiet.
 * `DueSubscriptionsTest.theTwoFailureTaxonomiesShareOneVocabulary` is the real guard.
 */
internal fun isTransientFailure(status: String?): Boolean =
    when (runCatching { status?.let(SubscriptionSyncFailure::valueOf) }.getOrNull()) {
        SubscriptionSyncFailure.Unreachable,
        SubscriptionSyncFailure.TimedOut,
        SubscriptionSyncFailure.TlsFailure,
        SubscriptionSyncFailure.ServerError,
        -> true

        SubscriptionSyncFailure.HwidRequired,
        SubscriptionSyncFailure.NotFound,
        SubscriptionSyncFailure.DeviceLimitReached,
        SubscriptionSyncFailure.ClientError,
        null,
        -> false
    }

/**
 * How long to wait before re-attempting a subscription that has been failing transiently, given
 * how far past its own refresh deadline it already is.
 *
 * Backs off geometrically without storing an attempt counter: each retry pushes [overdueBy] up by
 * the delay it just waited, so the next delay is `1 + 1/[BACKOFF_DIVISOR]` times the last. A blip
 * costs [MIN_RETRY_MILLIS] and no more; a genuinely dead host settles at the provider's own
 * interval rather than polling forever. Deriving it from the clock instead of a counter is what
 * keeps this a pure function and off the Room schema.
 */
internal fun retryDelayMillis(
    overdueBy: Long,
    intervalMillis: Long,
): Long =
    (overdueBy / BACKOFF_DIVISOR)
        .coerceIn(MIN_RETRY_MILLIS, maxOf(MIN_RETRY_MILLIS, intervalMillis))

/**
 * The next refresh deadline for one subscription.
 *
 * `lastAttemptedAt` — not `lastFetchedAt` — is the base in every branch: retry pacing follows
 * every attempt, while the last server-bearing success remains UI history. What the status
 * changes is the *step*, per [isTransientFailure]: a permanent failure and a success both wait
 * the provider's full interval, and only a transient failure gets [retryDelayMillis].
 *
 * `lastFetchedAt` does appear in the transient branch, as the anchor for "how overdue is this
 * already": a subscription that has never once succeeded has no such anchor, which is why
 * [StoredSubscription.createdAt] stands in for it there.
 */
internal fun nextAttemptDueAt(
    now: Long,
    subscription: StoredSubscription,
    intervalHours: Int,
): Long {
    // Clamped to now for the same clock-movement reason openTriggerAllows passes a future stamp:
    // an attempt stamped in the future would otherwise schedule the next one that far out again,
    // and the interval trigger's `dueAtEpochMillis <= now` filter would never pass until the clock
    // caught up. Clamping bounds the damage at one interval instead of one clock error.
    val lastAttemptedAt = (subscription.lastAttemptedAt ?: return now).coerceAtMost(now)
    val intervalMillis = intervalHours * HOUR_MILLIS
    // Single-expression `step` rather than an early return per branch: detekt's ReturnCount caps
    // functions at two, and the guard-clause form this started from used three.
    val step =
        if (isTransientFailure(subscription.lastFetchStatus)) {
            val healthyUntil = (subscription.lastFetchedAt ?: subscription.createdAt) + intervalMillis
            retryDelayMillis((lastAttemptedAt - healthyUntil).coerceAtLeast(0), intervalMillis)
        } else {
            intervalMillis
        }
    return lastAttemptedAt + step
}

/**
 * The on-open trigger's only rate limit: has this subscription been left alone long enough?
 *
 * Deliberately *not* the refresh interval — see [RefreshScheduler.refreshDue]'s `onOpen` path for
 * why applying that gate here is what made the switch inert. A subscription that has never been
 * attempted always passes.
 *
 * The floor is shorter after a failure ([OPEN_REFRESH_RETRY_GAP_MILLIS]) than after a success
 * ([OPEN_REFRESH_MIN_GAP_MILLIS]), because `lastAttemptedAt` advances on failure too and a single
 * floor keyed on it punishes the user for a blip. Concretely: open the app in a lift with no
 * signal, every subscription fails and stamps the time; step out a minute later and reopen, and a
 * flat five-minute floor refuses — the one manual lever the user has is dead until the interval
 * path comes round. That is a weaker form of the staleness `349452b` fixed, on the one path
 * WorkManager's network constraint cannot cover.
 *
 * A stamp in the *future* passes unconditionally. It means the clock moved (a correction, a
 * timezone change, a bad NTP sync), not that the subscription was just refreshed; without this,
 * `now - lastAttemptedAt` stays negative and both triggers refuse the subscription until the clock
 * catches up — up to a day of no refresh with no in-app way out.
 */
internal fun openTriggerAllows(
    now: Long,
    lastAttemptedAt: Long?,
    lastFetchStatus: String?,
): Boolean {
    val elapsed = lastAttemptedAt?.let { now - it } ?: return true
    val floor =
        if (lastFetchStatus == null) OPEN_REFRESH_MIN_GAP_MILLIS else OPEN_REFRESH_RETRY_GAP_MILLIS
    return elapsed < 0 || elapsed >= floor
}

/** Turns one stored subscription row into the scheduler input used by [dueChecks]. */
internal fun dueCheckFor(
    subscription: StoredSubscription,
    now: Long,
    intervalHours: Int,
): DueCheck =
    DueCheck(
        subscriptionId = subscription.id,
        dueAtEpochMillis = nextAttemptDueAt(now, subscription, intervalHours),
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
                        // Without this the job fires whether or not there is a network, the fetch
                        // fails as Unreachable, and — since lastAttemptedAt deliberately advances
                        // on failure to stop a failing subscription rebuilding the chain
                        // immediately — the subscription then waits a FULL interval before trying
                        // again. One offline moment therefore costs up to 12 hours of staleness at
                        // the default interval. M4's device run caught exactly that: both
                        // subscriptions attempted while the device had no connectivity and both
                        // recorded Unreachable. Deferring on the constraint is what WorkManager is
                        // for, and it leaves the anti-thrash pacing intact for real failures
                        // (a 500, a timeout) that genuinely should back off.
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
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
    suspend fun refreshDue(
        onOpen: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val targets =
            if (onOpen) {
                openTriggerTargets(now)
            } else {
                dueChecks(now).filter { it.dueAtEpochMillis <= now }.map { it.subscriptionId }
            }
        targets.forEach { syncer.sync(it) }
    }

    /**
     * The subscriptions the on-open trigger should sync — **ignoring the refresh interval.**
     *
     * This is the whole point of `subscription-auto-update-open-enable` being a separate switch
     * from `subscription-auto-update-enable`, and getting it wrong made the switch inert. When the
     * interval gate ran first and the toggle merely filtered what survived it, no path existed in
     * which turning the toggle *on* caused a fetch: it could only ever subtract one the interval
     * trigger was already going to do. A user with a 12-hour provider interval could open the app
     * fifty times and watch a row labelled "Refresh when app opens" do nothing — M4's device run,
     * reported exactly that way.
     *
     * The general kill switch still applies: a provider that disabled auto-update entirely
     * disabled it for both triggers, and [dueChecks] applies the same gate on the interval path.
     * [OPEN_REFRESH_MIN_GAP_MILLIS] replaces the interval as the only rate limit here.
     */
    private suspend fun openTriggerTargets(now: Long): List<Long> =
        subscriptions.observeSubscriptions().first().mapNotNull { subscription ->
            val id = subscription.id
            id.takeIf {
                openTriggerAllows(now, subscription.lastAttemptedAt, subscription.lastFetchStatus) &&
                    autoUpdateEnabled(id) &&
                    openRefreshEnabled(id)
            }
        }

    private suspend fun autoUpdateEnabled(id: Long): Boolean =
        scheduledAutoUpdateEnabled(
            subscriptions.effective(id, KEY_AUTO_UPDATE, default = "true").value,
        )

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
            if (!autoUpdateEnabled(subscription.id)) return@mapNotNull null

            val intervalDefault = DEFAULT_INTERVAL_HOURS.toString()
            val intervalValue = subscriptions.effective(subscription.id, KEY_INTERVAL, intervalDefault).value
            val hours = resolveIntervalHours(intervalValue)

            dueCheckFor(subscription, now, hours)
        }
}
