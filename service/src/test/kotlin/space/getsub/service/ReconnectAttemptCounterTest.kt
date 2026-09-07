// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Fix round 1, Finding 1: `TunnelService` cannot be unit-tested directly
 * (§11), so the retry loop's attempt counter is extracted here to make the
 * one behaviour most likely to be missed — a successful connect resetting the
 * count — a real, checked invariant rather than something read off private
 * service state.
 */
class ReconnectAttemptCounterTest {
    @Test
    fun incrementsAcrossSuccessiveFailures() {
        val counter = ReconnectAttemptCounter()

        counter.next() shouldBe 1
        counter.next() shouldBe 2
        counter.next() shouldBe 3
    }

    /** The one most likely to be missed: a long-lived session's second outage must not start at the cap. */
    @Test
    fun resetReturnsTheNextAttemptToOne() {
        val counter = ReconnectAttemptCounter()
        counter.next()
        counter.next()

        counter.reset()

        counter.next() shouldBe 1
    }

    @Test
    fun resetOnAFreshCounterIsANoOp() {
        val counter = ReconnectAttemptCounter()

        counter.reset()

        counter.next() shouldBe 1
    }
}
