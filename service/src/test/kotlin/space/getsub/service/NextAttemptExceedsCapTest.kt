// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Retryability

/**
 * Fix round 1, Finding 2: `reconcile` refuses to retry a `RetryableCapped`
 * reason once `attempt >= TUN_ESTABLISH_ATTEMPT_CAP`, but nothing converts the
 * resulting `Reconnecting` into `Failed` — without this function's caller
 * acting on it, the session sits in `Reconnecting` forever while having
 * actually given up.
 */
class NextAttemptExceedsCapTest {
    @Test
    fun underCapKeepsRetrying() {
        nextAttemptExceedsCap(Retryability.RetryableCapped, nextAttempt = 2) shouldBe false
    }

    @Test
    fun reachingTheCapIsTerminal() {
        nextAttemptExceedsCap(Retryability.RetryableCapped, nextAttempt = 3) shouldBe true
    }

    @Test
    fun exceedingTheCapIsStillTerminal() {
        nextAttemptExceedsCap(Retryability.RetryableCapped, nextAttempt = 4) shouldBe true
    }

    /** Spec §2.4: unbounded while a network exists — that is the point, not a gap. */
    @Test
    fun retryableIsNeverCapped() {
        nextAttemptExceedsCap(Retryability.Retryable, nextAttempt = 1000) shouldBe false
    }

    /** Not a real call site — callers only invoke this for a retryable reason — but pinned regardless. */
    @Test
    fun terminalIsNeverTreatedAsCapped() {
        nextAttemptExceedsCap(Retryability.Terminal, nextAttempt = 1000) shouldBe false
    }
}
