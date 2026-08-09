// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import art.yniyniyni.subspace.core.data.StoredSubscription
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

private const val HOUR = 3_600_000L

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
    fun `a failed or empty attempt waits for the provider interval`() {
        val oldSuccess = 1_000_000L
        val newerAttempt = oldSuccess + HOUR
        val subscription =
            StoredSubscription(
                id = 1,
                groupId = 1,
                url = "https://example.com/sub",
                userAgentOverride = null,
                hwidEnabled = true,
                lastFetchedAt = oldSuccess,
                lastAttemptedAt = newerAttempt,
                lastFetchStatus = "TimedOut",
                lastFetchDetail = "TimedOut",
            )

        // dueChecks() calls dueCheckFor(): retry pacing follows every attempt, while the old
        // server-bearing success remains UI history only.
        dueCheckFor(subscription, now = newerAttempt + HOUR, intervalHours = 6).dueAtEpochMillis shouldBe
            newerAttempt + 6 * HOUR
    }
}
