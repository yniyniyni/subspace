// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult

class LatencyCacheTest {
    private val cache = LatencyCache()

    @Test
    fun `a stored result is readable and clears that row's testing flag`() {
        cache.markTesting(listOf(1L, 2L))
        cache.testing.value shouldBe setOf(1L, 2L)

        cache.put(1L, LatencyResult.ok(42))

        cache.results.value[1L] shouldBe LatencyResult.ok(42)
        cache.testing.value shouldBe setOf(2L)
    }

    @Test
    fun `finish clears rows that never produced a result rather than inventing one`() {
        cache.markTesting(listOf(1L, 2L))
        cache.put(1L, LatencyResult.ok(42))

        cache.finish(listOf(1L, 2L))

        cache.testing.value shouldBe emptySet()
        // A cancelled or superseded measurement produced no number. Recording
        // even a failure would claim a test happened that did not (§10.1).
        cache.results.value.containsKey(2L) shouldBe false
    }

    @Test
    fun `a re-test replaces the previous result`() {
        cache.put(1L, LatencyResult.ok(42))
        cache.put(1L, LatencyResult.failed(LatencyOutcome.UNREACHABLE))

        cache.results.value[1L] shouldBe LatencyResult.failed(LatencyOutcome.UNREACHABLE)
    }

    @Test
    fun `an unmeasured profile is absent rather than zero`() {
        cache.results.value.containsKey(99L) shouldBe false
    }

    @Test
    fun `a launch run can be claimed once per group per session`() {
        // This is what makes ping-on-launch fire once per app start rather than
        // once per navigation to the list.
        cache.claimLaunchRun(7L) shouldBe true
        cache.claimLaunchRun(7L) shouldBe false
        cache.claimLaunchRun(8L) shouldBe true
    }
}
