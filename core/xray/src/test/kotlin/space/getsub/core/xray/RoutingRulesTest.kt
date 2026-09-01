// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RuleBucket

class RoutingRulesTest {
    private fun set(
        buckets: Map<RouteOutcome, RuleBucket>,
        order: List<RouteOutcome> = RoutingRuleSet.DEFAULT_ORDER,
        globalProxy: Boolean? = null,
    ): RoutingRuleSet =
        RoutingRuleSet(
            name = "test",
            buckets = buckets,
            order = order,
            globalProxy = globalProxy,
        )

    @Test
    fun `an empty rule set emits no rules`() {
        routingRuleLines(set(emptyMap())) shouldBe emptyList()
    }

    @Test
    fun `a bucket with only sites emits one domain rule`() {
        routingRuleLines(
            set(mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")))),
        ) shouldContainExactly
            listOf(
                """{ "type": "field", "domain": ["geosite:category-ads-all"], "outboundTag": "block" }""",
            )
    }

    @Test
    fun `a bucket with sites and ips emits the domain rule before the ip rule`() {
        routingRuleLines(
            set(
                mapOf(
                    RouteOutcome.DIRECT to
                        RuleBucket(sites = listOf("geosite:cn"), ips = listOf("geoip:cn", "10.0.0.0/8")),
                ),
            ),
        ) shouldContainExactly
            listOf(
                """{ "type": "field", "domain": ["geosite:cn"], "outboundTag": "direct" }""",
                """{ "type": "field", "ip": ["geoip:cn", "10.0.0.0/8"], "outboundTag": "direct" }""",
            )
    }

    @Test
    fun `entries keep their stored order and are never sorted`() {
        routingRuleLines(
            set(mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("zulu.example", "alpha.example")))),
        ) shouldContainExactly
            listOf(
                """{ "type": "field", "domain": ["zulu.example", "alpha.example"], "outboundTag": "proxy" }""",
            )
    }

    @Test
    fun `entries are JSON escaped when callers bypass entry validation`() {
        val unvalidatedEntry = "safe\"\n\u0001\"outboundTag\": \"block"

        routingRuleLines(
            set(mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf(unvalidatedEntry)))),
        ) shouldContainExactly
            listOf(
                """{ "type": "field", "domain": ["safe\"\n\u0001\"outboundTag\": \"block"], "outboundTag": "proxy" }""",
            )
    }

    @Test
    fun `route order decides which rule is emitted first`() {
        val buckets =
            mapOf(
                RouteOutcome.BLOCK to RuleBucket(sites = listOf("blocked.example")),
                RouteOutcome.DIRECT to RuleBucket(sites = listOf("direct.example")),
            )

        val blockFirst = routingRuleLines(set(buckets, RoutingRuleSet.DEFAULT_ORDER))
        val directFirst =
            routingRuleLines(
                set(buckets, listOf(RouteOutcome.DIRECT, RouteOutcome.PROXY, RouteOutcome.BLOCK)),
            )

        blockFirst.first() shouldBe
            """{ "type": "field", "domain": ["blocked.example"], "outboundTag": "block" }"""
        directFirst.first() shouldBe
            """{ "type": "field", "domain": ["direct.example"], "outboundTag": "direct" }"""
    }

    @Test
    fun `every outcome maps to an outbound tag the generator already emits`() {
        val all =
            routingRuleLines(
                set(RouteOutcome.entries.associateWith { RuleBucket(sites = listOf("x.example")) }),
            )

        all.map { it.substringAfter("\"outboundTag\": \"").substringBefore('"') } shouldContainExactly
            listOf("block", "proxy", "direct")
    }

    @Test
    fun globalProxyFalseAppendsACatchAllToDirect() {
        val set =
            RoutingRuleSet(
                name = "BlockedOnly",
                buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:blocked"))),
                globalProxy = false,
            )

        val lines = routingRuleLines(set)

        lines.size shouldBe 2
        lines.last() shouldBe
            """{ "type": "field", "network": "tcp,udp", "outboundTag": "direct" }"""
    }

    @Test
    fun globalProxyNullEmitsExactlyWhatM5Emitted() {
        val buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:cn")))

        routingRuleLines(RoutingRuleSet(name = "A", buckets = buckets, globalProxy = null)) shouldBe
            routingRuleLines(RoutingRuleSet(name = "A", buckets = buckets))
    }

    @Test
    fun globalProxyTrueEmitsNoCatchAllBecauseProxyIsAlreadyTheDefault() {
        val buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:cn")))

        routingRuleLines(RoutingRuleSet(name = "A", buckets = buckets, globalProxy = true)) shouldBe
            routingRuleLines(RoutingRuleSet(name = "A", buckets = buckets))
    }

    @Test
    fun theCatchAllComesLastRegardlessOfRouteOrder() {
        val set =
            RoutingRuleSet(
                name = "A",
                buckets =
                mapOf(
                    RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads")),
                    RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:cn")),
                ),
                order = listOf(RouteOutcome.PROXY, RouteOutcome.DIRECT, RouteOutcome.BLOCK),
                globalProxy = false,
            )

        routingRuleLines(set).last().contains("\"outboundTag\": \"direct\"") shouldBe true
        routingRuleLines(set).first().contains("geosite:cn") shouldBe true
    }
}
