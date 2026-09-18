// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.core.model.TagTraffic

class MetricsClientTest {
    // Shape read from app/metrics/metrics.go:175-201 at xray-core v26.7.11.
    private val payload =
        """
        {
          "cmdline": ["/system/bin/app_process"],
          "stats": {
            "inbound":  { "socks-in": { "uplink": 10, "downlink": 20 } },
            "outbound": {
              "proxy":  { "uplink": 100, "downlink": 200 },
              "direct": { "uplink": 5,   "downlink": 7 }
            },
            "user": {}
          },
          "observatory": {}
        }
        """.trimIndent()

    @Test
    fun `reads outbound tags with both directions`() {
        val tags = parseMetricsPayload(payload).associateBy { it.tag }
        assertEquals(100L, tags.getValue("proxy").uplinkBytes)
        assertEquals(200L, tags.getValue("proxy").downlinkBytes)
        assertEquals(5L, tags.getValue("direct").uplinkBytes)
    }

    @Test
    fun `ignores inbound and user buckets`() {
        assertEquals(setOf("proxy", "direct"), parseMetricsPayload(payload).map { it.tag }.toSet())
    }

    @Test
    fun `an empty stats object yields no rows rather than throwing`() {
        val empty = """{"stats":{"inbound":{},"outbound":{},"user":{}}}"""
        assertEquals(emptyList<TagTraffic>(), parseMetricsPayload(empty))
    }

    @Test
    fun `malformed JSON yields no rows rather than throwing`() {
        assertEquals(emptyList<TagTraffic>(), parseMetricsPayload("not json at all"))
    }

    @Test
    fun `a tag reporting only one direction reads zero for the other`() {
        // Real: a counter is registered lazily, so a tag that has only sent
        // appears with uplink alone.
        val oneWay = """{"stats":{"inbound":{},"outbound":{"proxy":{"uplink":42}},"user":{}}}"""
        val row = parseMetricsPayload(oneWay).single()
        assertEquals(42L, row.uplinkBytes)
        assertEquals(0L, row.downlinkBytes)
    }
}
