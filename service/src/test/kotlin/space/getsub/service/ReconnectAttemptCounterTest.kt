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
 *
 * Fix round 2: split into [ReconnectAttemptCounter.peekNext] (read-only) and
 * [ReconnectAttemptCounter.commit] (the only mutator) so `TunnelService` can
 * compute a trial attempt number before it knows whether this generation is
 * still current, without that computation being able to leave a lasting
 * increment behind if it turns out not to be — a superseded generation calls
 * [ReconnectAttemptCounter.peekNext] and nothing else, ever. These tests pin
 * that split directly, since it is the whole fix.
 */
class ReconnectAttemptCounterTest {
    @Test
    fun peekingRepeatedlyWithoutCommittingReturnsTheSameValue() {
        val counter = ReconnectAttemptCounter()

        // Models a superseded generation: it computes a trial value (or several,
        // if re-entered) but its settle() never commits, because it never
        // committed anything is exactly the point being pinned here.
        counter.peekNext() shouldBe 1
        counter.peekNext() shouldBe 1
        counter.peekNext() shouldBe 1
    }

    @Test
    fun commitAdvancesWhatTheNextPeekReturns() {
        val counter = ReconnectAttemptCounter()

        counter.commit(counter.peekNext())
        counter.peekNext() shouldBe 2
        counter.commit(counter.peekNext())
        counter.peekNext() shouldBe 3
    }

    /**
     * The exact shape of the bug this round fixes: a stale generation peeks
     * (but never commits) a trial value; the real, current-generation failure
     * that follows must read the same number the stale one would have, not
     * one inflated by the stale peek.
     */
    @Test
    fun aStaleUncommittedPeekDoesNotAffectTheNextRealAttempt() {
        val counter = ReconnectAttemptCounter()
        counter.commit(counter.peekNext()) // one real, committed failure: attempt 1

        val staleTrial = counter.peekNext() // a second, superseded failure peeks...
        staleTrial shouldBe 2
        // ...but its settle() is superseded, so nothing calls commit(staleTrial).

        // The real next failure reads the same trial value the stale one saw —
        // proof the stale peek left nothing behind.
        counter.peekNext() shouldBe 2
    }

    /** The one most likely to be missed: a long-lived session's second outage must not start at the cap. */
    @Test
    fun resetReturnsTheNextPeekToOne() {
        val counter = ReconnectAttemptCounter()
        counter.commit(counter.peekNext())
        counter.commit(counter.peekNext())

        counter.reset()

        counter.peekNext() shouldBe 1
    }

    @Test
    fun resetOnAFreshCounterIsANoOp() {
        val counter = ReconnectAttemptCounter()

        counter.reset()

        counter.peekNext() shouldBe 1
    }
}
