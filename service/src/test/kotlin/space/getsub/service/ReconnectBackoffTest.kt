// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun theFirstRetryIsPrompt() {
        ReconnectBackoff.delayMillisFor(attempt = 1) shouldBe 1_000L
    }

    @Test
    fun itDoublesPerAttempt() {
        ReconnectBackoff.delayMillisFor(attempt = 2) shouldBe 2_000L
        ReconnectBackoff.delayMillisFor(attempt = 3) shouldBe 4_000L
        ReconnectBackoff.delayMillisFor(attempt = 4) shouldBe 8_000L
    }

    /**
     * Unbounded doubling would put a wanted session hours away from its next
     * attempt after an overnight outage — indistinguishable, to the user, from
     * the app having given up.
     */
    @Test
    fun itIsCappedAtOneMinute() {
        ReconnectBackoff.delayMillisFor(attempt = 20) shouldBe BACKOFF_CAP_MILLIS
        (1..64).forEach { attempt ->
            ReconnectBackoff.delayMillisFor(attempt) shouldBeLessThanOrEqual BACKOFF_CAP_MILLIS
        }
    }

    /**
     * A very large attempt count must not overflow into a negative delay, which
     * would schedule immediately and spin. Reachable after a long outage.
     */
    @Test
    fun aHugeAttemptCountDoesNotOverflow() {
        ReconnectBackoff.delayMillisFor(attempt = Int.MAX_VALUE) shouldBe BACKOFF_CAP_MILLIS
    }

    @Test
    fun attemptZeroOrBelowIsTreatedAsTheFirst() {
        ReconnectBackoff.delayMillisFor(attempt = 0) shouldBe 1_000L
        ReconnectBackoff.delayMillisFor(attempt = -5) shouldBe 1_000L
    }
}
