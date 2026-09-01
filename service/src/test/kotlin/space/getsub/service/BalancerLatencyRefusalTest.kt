// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.LatencyOutcome

/**
 * The rule that keeps a balancer row's latency from describing one arbitrary member.
 *
 * Found on device 2026-09-01: the provider's `AUTO_BALANCER` set carried eight hosts whose
 * config profiles had no node assigned, the collapsed row kept the first of them, and the
 * row read "No response" while traffic flowed fine over the healthy members.
 */
class BalancerLatencyRefusalTest {
    private fun config(
        outbounds: String,
        balancers: String = "",
    ) = """
        {
          "routing": { "rules": [ { "network": "tcp,udp", "outboundTag": "proxy" } ]$balancers },
          "outbounds": [ $outbounds ]
        }
    """.trimIndent()

    private val oneServer = """{ "tag": "proxy", "protocol": "vless" }, { "tag": "direct", "protocol": "freedom" }"""
    private val balancerMembers =
        """
        { "tag": "proxy", "protocol": "vless" }, { "tag": "proxy-2", "protocol": "vless" },
        { "tag": "direct", "protocol": "freedom" }
        """.trimIndent()

    @Test
    fun `a single-server config stays measurable`() {
        balancerLatencyRefusal(config(oneServer)) shouldBe null
    }

    @Test
    fun `a balancer row refuses rather than reporting its first member`() {
        val balancers = ""","balancers": [ { "tag": "Auto", "selector": ["proxy"] } ]"""
        balancerLatencyRefusal(config(balancerMembers, balancers)) shouldBe LatencyOutcome.UNSUPPORTED
    }

    @Test
    fun `a TYPED row carries no rawJson and is unaffected`() {
        balancerLatencyRefusal(null) shouldBe null
    }

    @Test
    fun `malformed bytes do not refuse — the structural read reports no balancer`() {
        balancerLatencyRefusal("{ not json") shouldBe null
    }
}
