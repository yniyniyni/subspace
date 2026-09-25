// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricsBlocksTest {
    @Test
    fun `emits all three blocks, because metrics alone counts nothing`() {
        val blocks = metricsBlocks(port = 41234)
        assertTrue("stats" in blocks)
        assertTrue("policy" in blocks)
        assertTrue("metrics" in blocks)
    }

    @Test
    fun `policy enables every stats flag`() {
        val system = (metricsBlocks(41234)["policy"] as JsonObject)["system"]!!.jsonObject
        listOf(
            "statsInboundUplink",
            "statsInboundDownlink",
            "statsOutboundUplink",
            "statsOutboundDownlink",
        ).forEach { flag ->
            assertEquals("$flag must be true", "true", system[flag]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `binds loopback only, never all interfaces`() {
        val listen = (metricsBlocks(41234)["metrics"] as JsonObject)["listen"]!!.jsonPrimitive.content
        // ARCHITECTURE.md §6: never 0.0.0.0 — that turns the phone into an open
        // proxy on the local network.
        assertEquals("127.0.0.1:41234", listen)
    }
}
