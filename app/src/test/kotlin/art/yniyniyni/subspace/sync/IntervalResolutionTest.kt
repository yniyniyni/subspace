// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Review round 1, Important 3: `SubscriptionRepository.pin(id, key, value)` accepts an arbitrary
 * `String` with no validation against `DirectiveKind`'s bounds — unlike the provider path, which
 * `DirectiveValidator` already rejects out-of-range `profile-update-interval` values on (never
 * clamps). No `pin()` call site exists yet (Task 15 adds the UI), so [resolveIntervalHours] is the
 * backstop against a future hostile or malformed pin, not against anything reachable today.
 */
class IntervalResolutionTest {
    @Test
    fun `a well-formed value is used as-is`() {
        resolveIntervalHours("6") shouldBe 6
    }

    @Test
    fun `a missing value falls back to the default`() {
        resolveIntervalHours(null) shouldBe DEFAULT_INTERVAL_HOURS
    }

    @Test
    fun `a non-numeric value falls back to the default`() {
        resolveIntervalHours("not-a-number") shouldBe DEFAULT_INTERVAL_HOURS
    }

    @Test
    fun `a value below the minimum is clamped up to one hour`() {
        resolveIntervalHours("0") shouldBe MIN_INTERVAL_HOURS
        resolveIntervalHours("-999") shouldBe MIN_INTERVAL_HOURS
    }

    @Test
    fun `a value above the maximum is clamped down to one year`() {
        resolveIntervalHours("999999999") shouldBe MAX_INTERVAL_HOURS
    }
}
