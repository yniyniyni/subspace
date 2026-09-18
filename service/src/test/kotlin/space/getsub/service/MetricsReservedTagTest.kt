// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.core.xray.ComposeFailure
import space.getsub.core.xray.METRICS_RESERVED_TAG

class MetricsReservedTagTest {
    @Test
    fun `Metrics is reserved only while the breakdown is on`() {
        assertEquals(
            setOf("direct", "block", "dns-out"),
            reservedOutboundTags(breakdownEnabled = false),
        )
        assertEquals(
            setOf("direct", "block", "dns-out", METRICS_RESERVED_TAG),
            reservedOutboundTags(breakdownEnabled = true),
        )
    }

    @Test
    fun `a config defining Metrics is refused only while the breakdown is on`() {
        assertEquals(
            false,
            collidesWithReservedTag(setOf("proxy", "Metrics"), breakdownEnabled = false),
        )
        assertEquals(
            true,
            collidesWithReservedTag(setOf("proxy", "Metrics"), breakdownEnabled = true),
        )
    }

    @Test
    fun `composePassthrough's override check refuses an existing Metrics outbound only while the breakdown is on`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "Metrics", "protocol": "freedom" }
              ]
            }
            """.trimIndent()

        assertEquals(null, passthroughOverrideFailure(json, breakdownEnabled = false))
        assertEquals(
            ComposeFailure.IncompatibleOverrideOutbound,
            passthroughOverrideFailure(json, breakdownEnabled = true),
        )
    }
}
