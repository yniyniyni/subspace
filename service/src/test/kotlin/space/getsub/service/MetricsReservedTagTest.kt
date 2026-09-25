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

    // --- resolveOverrideBreakdown (review finding I4) ---
    //
    // Before this, a Metrics collision refused the whole connect. Spec §2.3:
    // "a passthrough config defining an outbound called Metrics is not
    // exotic", so a switch this app's own UI describes as a diagnostic must
    // not be able to take the tunnel down with it.

    @Test
    fun `no collision keeps the breakdown exactly as requested`() {
        val json = """{"outbounds":[{"tag":"proxy","protocol":"vless"}]}"""

        assertEquals(
            OverrideBreakdownDecision.Proceed(useBreakdown = true),
            resolveOverrideBreakdown(json, breakdownEnabled = true),
        )
        assertEquals(
            OverrideBreakdownDecision.Proceed(useBreakdown = false),
            resolveOverrideBreakdown(json, breakdownEnabled = false),
        )
    }

    @Test
    fun `an existing Metrics outbound degrades the breakdown instead of refusing the connect`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "Metrics", "protocol": "freedom" }
              ]
            }
            """.trimIndent()

        assertEquals(
            OverrideBreakdownDecision.Proceed(useBreakdown = false),
            resolveOverrideBreakdown(json, breakdownEnabled = true),
        )
        // Nothing to degrade when it was never requested — the config runs as before.
        assertEquals(
            OverrideBreakdownDecision.Proceed(useBreakdown = false),
            resolveOverrideBreakdown(json, breakdownEnabled = false),
        )
    }

    @Test
    fun `a real reserved-tag collision still refuses, breakdown or not`() {
        // "direct" already exists as something other than freedom -- this is
        // the M7-era collision resolveOverrideBreakdown must never talk away,
        // because it has nothing to do with the diagnostic.
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "direct", "protocol": "vless" }
              ]
            }
            """.trimIndent()

        assertEquals(
            OverrideBreakdownDecision.Refused(ComposeFailure.IncompatibleOverrideOutbound),
            resolveOverrideBreakdown(json, breakdownEnabled = true),
        )
        assertEquals(
            OverrideBreakdownDecision.Refused(ComposeFailure.IncompatibleOverrideOutbound),
            resolveOverrideBreakdown(json, breakdownEnabled = false),
        )
    }
}
