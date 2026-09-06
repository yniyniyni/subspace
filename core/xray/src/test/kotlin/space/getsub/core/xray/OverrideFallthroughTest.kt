// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RuleBucket
import space.getsub.core.parser.OverrideTarget

/**
 * What the override branch says about traffic no rule matched.
 *
 * The typed path never needs to say anything: `appendOutbounds` writes `proxy`
 * first, and the core sends unmatched traffic to the first outbound — measured
 * as row `C1` of `docs/agent/research/2026-09-01-balancer-tag-binding.md`, where
 * a rule-less config sank into the `blackhole` sitting first in its `outbounds`.
 *
 * The override branch has no such guarantee. It replaces the stored config's
 * `routing` wholesale — deleting that config's own catch-all — while
 * `RawConfigComposer` keeps the config's `outbounds` in their original order and
 * only appends to them. So whatever the document happened to list first decided
 * where unmatched traffic went: a `freedom` outbound in first position sent all
 * of it outside the tunnel (§5.2), and a balancer config's unmatched traffic
 * bypassed the balancer this milestone exists to name.
 */
class OverrideFallthroughTest {
    private val settings =
        TunnelSettings(
            socksPort = 10808,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
        )

    private val dnsPlan =
        DnsPlan(
            servers = listOf(DnsServerSpec("8.8.8.8")),
            hosts = emptyMap(),
            fakeDns = false,
            directMatch = null,
            proxyMatch = null,
        )

    private fun ruleSet(
        buckets: Map<RouteOutcome, RuleBucket> = emptyMap(),
        globalProxy: Boolean? = null,
    ) = RoutingRuleSet(name = "test", buckets = buckets, globalProxy = globalProxy)

    private fun routingJson(
        routing: RoutingRuleSet?,
        target: OverrideTarget.Resolved,
        dns: DnsPlan? = null,
    ): String =
        XrayConfigGenerator
            .overrideBlocks(settings.copy(routing = routing, dns = dns), target)
            .routingJson

    private fun ruleLines(json: String): List<String> =
        json.lines().map { it.trim().trimEnd(',') }.filter { it.startsWith("{ \"type\"") }

    @Test
    fun `an empty rule set still names the target for traffic no rule matched`() {
        val rules = ruleLines(routingJson(ruleSet(globalProxy = true), OverrideTarget.ViaOutbound("proxy-auto")))

        rules shouldBe
            listOf("""{ "type": "field", "network": "tcp,udp", "outboundTag": "proxy-auto" }""")
    }

    @Test
    fun `an unspecified globalProxy names the target, matching the typed path's outbound order`() {
        // Null is "the profile did not say", which `RoutingRuleSet.globalProxy`
        // documents as preserving proxy-first fallthrough — not as declining to
        // route.
        val rules = ruleLines(routingJson(ruleSet(globalProxy = null), OverrideTarget.ViaOutbound("proxy-auto")))

        rules.last() shouldBe """{ "type": "field", "network": "tcp,udp", "outboundTag": "proxy-auto" }"""
    }

    @Test
    fun `a balancer target owns the fallthrough as well`() {
        // A1: `balancerTag` binds on the `network` catch-all shape, measured as
        // row Q1a of the balancer-tag-binding record.
        val rules =
            ruleLines(
                routingJson(
                    ruleSet(mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf("geosite:cn")))),
                    OverrideTarget.ViaBalancer("Auto_Balancer"),
                ),
            )

        rules shouldBe
            listOf(
                """{ "type": "field", "domain": ["geosite:cn"], "outboundTag": "direct" }""",
                """{ "type": "field", "network": "tcp,udp", "balancerTag": "Auto_Balancer" }""",
            )
    }

    @Test
    fun `globalProxy false keeps its direct catch-all and gains no second one`() {
        val json = routingJson(ruleSet(globalProxy = false), OverrideTarget.ViaOutbound("proxy-auto"))

        ruleLines(json) shouldBe
            listOf("""{ "type": "field", "network": "tcp,udp", "outboundTag": "direct" }""")
        json shouldNotContain """"outboundTag": "proxy-auto""""
    }

    @Test
    fun `a dns-only override names the target for unmatched traffic too`() {
        // Routing off, custom resolver on: the override still applies, so the
        // config's own catch-all is still deleted and still needs replacing.
        val rules = ruleLines(routingJson(routing = null, target = OverrideTarget.ViaOutbound("p"), dns = dnsPlan))

        rules.last() shouldBe """{ "type": "field", "network": "tcp,udp", "outboundTag": "p" }"""
        // The port-53 hijack must stay ahead of it or the resolver's own queries
        // would be swallowed by the catch-all instead of reaching `dns-out`.
        rules[rules.lastIndex - 1] shouldContain """"port": 53, "outboundTag": "dns-out""""
    }

    @Test
    fun `the typed path emits no fallthrough rule of its own`() {
        // `appendOutbounds` puts `proxy` first, so the typed path's fallthrough
        // is already correct and an extra rule would change bytes the golden
        // files pin. This is the constraint stated as an assertion.
        val generated =
            XrayConfigGenerator.generate(GOLDEN_PROFILE, settings.copy(routing = ruleSet(globalProxy = true)))

        (generated as ConfigResult.Ok).json shouldNotContain """"network": "tcp,udp""""
    }
}
