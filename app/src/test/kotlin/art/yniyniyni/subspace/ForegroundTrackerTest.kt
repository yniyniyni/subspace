// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The on-open trigger's gate. Every test here is a lifecycle sequence the framework really
 * produces; the counting rule shipped wrong once on a premise about one of them.
 */
class ForegroundTrackerTest {
    private var events = 0
    private val tracker = ForegroundTracker { events++ }

    @Test
    fun `a cold start is a foreground event`() {
        tracker.started()

        events shouldBe 1
    }

    @Test
    fun `a rotation is not a foreground event`() {
        // The regression this class was extracted for. One activity, no android:configChanges, so
        // the framework runs onStop -> onDestroy -> onCreate -> onStart with no overlap: the naive
        // 0 -> 1 counter sees a departure and a return, and fired a full refresh plus an
        // enqueueUniqueWork(REPLACE) on every rotation, theme switch and font-size change.
        tracker.started()
        events shouldBe 1

        tracker.stopped(isChangingConfigurations = true)
        tracker.started()

        events shouldBe 1
    }

    @Test
    fun `repeated rotations never accumulate a foreground event`() {
        tracker.started()
        repeat(5) {
            tracker.stopped(isChangingConfigurations = true)
            tracker.started()
        }

        events shouldBe 1
    }

    @Test
    fun `genuinely leaving and returning is a foreground event`() {
        tracker.started()
        tracker.stopped(isChangingConfigurations = false)
        tracker.started()

        events shouldBe 2
    }

    @Test
    fun `a rotation while backgrounded does not swallow the next real return`() {
        // The failure mode of a naive "skip the next start" flag: if a recreation is remembered
        // but the app then genuinely leaves, the pending recreation must not eat the real return.
        tracker.started()
        tracker.stopped(isChangingConfigurations = true)
        tracker.started() // recreation consumed, no event
        events shouldBe 1

        tracker.stopped(isChangingConfigurations = false)
        tracker.started()

        events shouldBe 2
    }

    @Test
    fun `handing one screen off to the next is not a foreground event`() {
        // A -> B navigation overlaps: B.onStart precedes A.onStop, so the count never reaches zero.
        tracker.started() // A
        tracker.started() // B starts before A stops
        tracker.stopped(isChangingConfigurations = false) // A

        events shouldBe 1
    }

    @Test
    fun `returning from a two-screen back stack fires exactly once`() {
        tracker.started() // A
        tracker.started() // B
        tracker.stopped(isChangingConfigurations = false) // A stops, B visible
        events shouldBe 1

        tracker.stopped(isChangingConfigurations = false) // B stops — app is now background
        tracker.started() // user returns

        events shouldBe 2
    }

    @Test
    fun `an unmatched stop cannot wedge the tracker shut`() {
        // The counter must not go negative: a negative count would make every subsequent start
        // miss the 0 -> 1 edge and the trigger would go silent for the life of the process.
        tracker.stopped(isChangingConfigurations = false)
        tracker.stopped(isChangingConfigurations = false)

        tracker.started()

        events shouldBe 1
    }
}
