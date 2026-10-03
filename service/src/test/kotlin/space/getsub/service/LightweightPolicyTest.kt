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
}
