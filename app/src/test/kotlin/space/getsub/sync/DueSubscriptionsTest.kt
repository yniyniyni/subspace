// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.sync

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.sync.SubscriptionSyncFailure

private const val HOUR = 3_600_000L
private const val MINUTE = 60_000L
private const val SECOND = 1_000L

class DueSubscriptionsTest {
    @Test
    fun `the earliest due time across all subscriptions wins`() {
        // One pending job regardless of subscription count (spec §8).
        val now = 1_000_000L
        nextDueAt(
            now,
            listOf(
                DueCheck(1, now + 24 * HOUR),
                DueCheck(2, now + 6 * HOUR),
                DueCheck(3, now + 12 * HOUR),
            ),
        ) shouldBe now + 6 * HOUR
    }

    @Test
    fun `an already-overdue subscription makes the next due time now`() {
        val now = 1_000_000L
        nextDueAt(now, listOf(DueCheck(1, now - HOUR))) shouldBe now
    }

    @Test
    fun `no subscriptions means nothing to schedule`() {
        nextDueAt(1_000_000L, emptyList()).shouldBeNull()
    }

    @Test
    fun `two subscriptions with different intervals keep their own cadences`() {
        // Spec §8: there is no app-wide refresh rate. Scheduling to the earliest
        // must not drag the later one forward.
        val now = 0L
        val hourly = DueCheck(1, now + HOUR)
        val daily = DueCheck(2, now + 24 * HOUR)

        nextDueAt(now, listOf(hourly, daily)) shouldBe now + HOUR
        // After the hourly one runs and re-stamps, the daily one is untouched.
        nextDueAt(now + HOUR, listOf(DueCheck(1, now + 2 * HOUR), daily)) shouldBe
            now + 2 * HOUR
    }

    @Test
    fun `both scheduler gates disable arbitrary non-enabling pins`() {
        scheduledAutoUpdateEnabled("0") shouldBe false
        scheduledAutoUpdateEnabled("no") shouldBe false
        openAutoUpdateEnabled("0") shouldBe false
        openAutoUpdateEnabled("no") shouldBe false
    }

    @Test
    fun `a permanent failure waits for the provider interval`() {
        val oldSuccess = 1_000_000L
        val newerAttempt = oldSuccess + HOUR
        // A wrong URL answers identically in fifteen minutes and in six hours, so it is paced like
        // a success: retry pacing follows every attempt, while the old server-bearing success
        // remains UI history only.
        val subscription = subscription(lastFetchedAt = oldSuccess, lastAttemptedAt = newerAttempt, status = "NotFound")

        dueCheckFor(subscription, now = newerAttempt + HOUR, intervalHours = 6).dueAtEpochMillis shouldBe
            newerAttempt + 6 * HOUR
    }

    @Test
    fun `an empty response is paced like a permanent failure, not retried fast`() {
        // NoServers is not a SubscriptionSyncFailure member — it is the syncer's own literal. An
        // unparseable status must fall on the conservative side, or every future status string
        // silently becomes a fast-retry one.
        isTransientFailure("NoServers") shouldBe false
        isTransientFailure(null) shouldBe false
        isTransientFailure("SomethingAVersionFromTheFutureWrote") shouldBe false
    }

    @Test
    fun `a transient failure retries in minutes rather than waiting out the interval`() {
        // The M4 device case, to scale: last success 01:13, provider interval 12 h, handshake
        // reset at 11:52. The old rule sent the next attempt to 23:52 — for a fault that cleared
        // itself by 12:10. The floor is what that blip should cost.
        val lastSuccess = 0L
        val failedAt = lastSuccess + 10 * HOUR + 39 * MINUTE
        val subscription =
            subscription(lastFetchedAt = lastSuccess, lastAttemptedAt = failedAt, status = "TlsFailure")

        dueCheckFor(subscription, now = failedAt, intervalHours = 12).dueAtEpochMillis shouldBe
            failedAt + 15 * MINUTE
    }

    @Test
    fun `every transient member retries fast and every permanent one does not`() {
        listOf("Unreachable", "TimedOut", "TlsFailure", "ServerError")
            .forEach { isTransientFailure(it) shouldBe true }
        listOf("HwidRequired", "NotFound", "DeviceLimitReached", "ClientError")
            .forEach { isTransientFailure(it) shouldBe false }
    }

    @Test
    fun `repeated transient failures back off geometrically instead of polling forever`() {
        // Each retry adds its own delay to how overdue the subscription is, so the next delay
        // grows by 1 + 1/BACKOFF_DIVISOR. No attempt counter, no schema change.
        val interval = 12 * HOUR
        retryDelayMillis(overdueBy = 0, intervalMillis = interval) shouldBe 15 * MINUTE
        retryDelayMillis(overdueBy = HOUR, intervalMillis = interval) shouldBe 15 * MINUTE
        retryDelayMillis(overdueBy = 4 * HOUR, intervalMillis = interval) shouldBe HOUR
        retryDelayMillis(overdueBy = 24 * HOUR, intervalMillis = interval) shouldBe 6 * HOUR
    }

    @Test
    fun `backoff never exceeds the provider's own interval`() {
        // A host that has been dead for a week must not be polled less often than a healthy one
        // would be — the interval is the ceiling, not a starting point.
        retryDelayMillis(overdueBy = 7 * 24 * HOUR, intervalMillis = 6 * HOUR) shouldBe 6 * HOUR
        retryDelayMillis(overdueBy = 7 * 24 * HOUR, intervalMillis = HOUR) shouldBe HOUR
    }

    @Test
    fun `a subscription that has never succeeded still backs off`() {
        // lastFetchedAt is null here, so there is no "healthy until" moment to measure staleness
        // from; createdAt stands in for it. Without that the overdue window would be measured from
        // the epoch and the very first failure would jump straight to the interval cap.
        val createdAt = 5_000_000L
        val failedAt = createdAt + 30 * MINUTE
        val subscription =
            subscription(
                lastFetchedAt = null,
                lastAttemptedAt = failedAt,
                status = "Unreachable",
                createdAt = createdAt,
            )

        dueCheckFor(subscription, now = failedAt, intervalHours = 6).dueAtEpochMillis shouldBe
            failedAt + 15 * MINUTE
    }

    @Test
    fun `a never-attempted subscription is due immediately`() {
        val now = 9_000_000L
        val subscription = subscription(lastFetchedAt = null, lastAttemptedAt = null, status = null)

        dueCheckFor(subscription, now = now, intervalHours = 6).dueAtEpochMillis shouldBe now
    }

    @Test
    fun `the open trigger throttles on recency, and never on the refresh interval`() {
        val now = 100 * HOUR

        // Not the interval — a 12-hour subscription refreshed six minutes ago is nowhere near
        // due, and the on-open trigger still takes it. That is the entire point of the switch.
        openTriggerAllows(now, now - 6 * MINUTE, null) shouldBe true
        openTriggerAllows(now, now - 4 * MINUTE, null) shouldBe false
        openTriggerAllows(now, now - 5 * MINUTE, null) shouldBe true
        openTriggerAllows(now, null, null) shouldBe true
    }

    @Test
    fun `a failed attempt gets a shorter floor than a successful one`() {
        // lastAttemptedAt advances on failure too, so one floor for both punishes the user for a
        // blip: fail in a lift at 10:00, step out at 10:01, and reopening the app would refuse
        // until 10:05 — the only manual lever, dead, for a fault that lasted a minute.
        val now = 100 * HOUR
        val twoMinutesAgo = now - 2 * MINUTE

        openTriggerAllows(now, twoMinutesAgo, "Unreachable") shouldBe true
        openTriggerAllows(now, twoMinutesAgo, null) shouldBe false
    }

    @Test
    fun `even a failing subscription is not refetched on every app switch`() {
        val now = 100 * HOUR

        openTriggerAllows(now, now - 30 * SECOND, "Unreachable") shouldBe false
        openTriggerAllows(now, now - MINUTE, "Unreachable") shouldBe true
    }

    @Test
    fun `a timestamp in the future does not lock the subscription out`() {
        // The clock moved — a correction, a timezone change, a bad NTP sync. Without this,
        // `now - lastAttemptedAt` stays negative and BOTH triggers refuse the subscription until
        // real time catches up, which for a day-long jump means a day with no refresh and no way
        // out from inside the app.
        val now = 100 * HOUR

        openTriggerAllows(now, now + 24 * HOUR, null) shouldBe true
        openTriggerAllows(now, now + 24 * HOUR, "Unreachable") shouldBe true
    }

    @Test
    fun `a future attempt stamp does not push the interval schedule a day out`() {
        val now = 100 * HOUR
        val subscription =
            subscription(lastFetchedAt = now, lastAttemptedAt = now + 24 * HOUR, status = null)

        // Clamped to now, so the next attempt is one interval away — not twenty-five.
        dueCheckFor(subscription, now = now, intervalHours = 12).dueAtEpochMillis shouldBe
            now + 12 * HOUR
    }

    @Test
    fun `every name this classifies is a name the taxonomy actually has`() {
        // isTransientFailure parses a persisted string into SubscriptionSyncFailure, so a string
        // it treats as transient must be a real member — otherwise the classification is dead code
        // that quietly returns false. `:app` cannot see FetchFailure (§4: only :core:data may
        // depend on :core:network), so the other half of this — that the persisted string really
        // is one of these names — is guarded in :core:data by
        // SubscriptionSyncFailureTest.theTwoFailureVocabulariesCannotDrift.
        val names = SubscriptionSyncFailure.entries.map { it.name }

        listOf("Unreachable", "TimedOut", "TlsFailure", "ServerError").forEach { transient ->
            names shouldContain transient
            isTransientFailure(transient) shouldBe true
        }
    }

    private fun subscription(
        lastFetchedAt: Long?,
        lastAttemptedAt: Long?,
        status: String?,
        createdAt: Long = 0L,
    ) = StoredSubscription(
        id = 1,
        groupId = 1,
        url = "https://example.com/sub",
        userAgentOverride = null,
        hwidEnabled = true,
        lastFetchedAt = lastFetchedAt,
        lastAttemptedAt = lastAttemptedAt,
        lastFetchStatus = status,
        lastFetchDetail = status,
        createdAt = createdAt,
    )
}
