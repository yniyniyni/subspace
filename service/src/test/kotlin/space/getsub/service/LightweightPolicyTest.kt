// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/** What lightweight mode switches off in `:bg`, and what it must leave alone. */
class LightweightPolicyTest {
    @Test
    fun `log capture runs only when lightweight mode is off`() {
        capturesSessionLog(lightweight = false) shouldBe true
        capturesSessionLog(lightweight = true) shouldBe false
    }

    @Test
    fun `traffic samples reach the UI only when lightweight mode is off`() {
        sendsTrafficToUi(lightweight = false) shouldBe true
        sendsTrafficToUi(lightweight = true) shouldBe false
    }

    @Test
    fun `lightweight mode overrides the per-route breakdown`() {
        breakdownEnabled(perTagBreakdown = true, lightweight = false) shouldBe true
        breakdownEnabled(perTagBreakdown = true, lightweight = true) shouldBe false
        breakdownEnabled(perTagBreakdown = false, lightweight = false) shouldBe false
    }

    /** The invariant that matters most: health sees every sample, lightweight or not. */
    @Test
    fun `a sample always reaches health, and reaches the UI only when lightweight is off`() {
        listOf(false, true).forEach { lightweight ->
            var toUi = 0
            var toHealth = 0
            deliverTrafficSample(lightweight, toUi = { toUi++ }, toHealth = { toHealth++ })
            toHealth shouldBe 1
            toUi shouldBe if (lightweight) 0 else 1
        }
    }

    /** Review finding: a stop that lands during the setting read must not leave capture running. */
    @Test
    fun `capture starts only for a session that is still current`() {
        var started = 0
        startCaptureIfCurrent(lightweight = false, stillCurrent = { false }) { started++ }
        startCaptureIfCurrent(lightweight = true, stillCurrent = { true }) { started++ }
        started shouldBe 0
        startCaptureIfCurrent(lightweight = false, stillCurrent = { true }) { started++ }
        started shouldBe 1
    }

    @Test
    fun `turning lightweight on mid-session stops breakdown polling`() {
        var polled = 0
        val poll: () -> List<String> = {
            polled++
            listOf()
        }
        tagsToPoll(lightweight = true, poll) shouldBe emptyList()
        polled shouldBe 0
        tagsToPoll(lightweight = false, poll)
        polled shouldBe 1
    }
}
