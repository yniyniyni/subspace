// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RuleBucket
import space.getsub.core.parser.OverrideTarget

/**
 * Properties every override must hold, asserted over a matrix rather than one
 * case at a time.
 *
 * `OverrideFallthroughTest` pins the individual shapes that were broken. This
 * file exists because pinning shapes is what let them break: each defect found
 * in this milestone slipped through a suite of examples that simply did not
 * contain the shape in question, and each fix added the one example that was
 * missing. A property holds for shapes nobody thought to enumerate, including a
 * future emitter that appends a rule in the wrong place.
 */
class OverrideInvariantsTest {
    private val settings =
        TunnelSettings(socksPort = 10808, dnsServer = "1.1.1.1", enableSniffing = true)

    private val dnsPlan =
        DnsPlan(
            servers = listOf(DnsServerSpec("8.8.8.8")),
            hosts = emptyMap(),
            fakeDns = false,
            directMatch = "8.8.8.8",
            proxyMatch = "cloudflare-dns.com",
        )

    /** Every rule-set shape the app can hand the override, crossed with `globalProxy`. */
    private fun ruleSets(): List<Pair<String, RoutingRuleSet?>> {
        val buckets =
            mapOf(
                "empty" to emptyMap(),
                "direct-only" to mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf("a.test"))),
                "proxy-only" to mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("b.test"))),
                "block-and-proxy" to
                    mapOf(
                        RouteOutcome.BLOCK to RuleBucket(sites = listOf("c.test")),
                        RouteOutcome.PROXY to RuleBucket(ips = listOf("10.0.0.0/8")),
                    ),
            )
        return buildList {
            add("no rule set" to null)
            buckets.forEach { (name, b) ->
                listOf(null, true, false).forEach { gp ->
                    add("$name/globalProxy=$gp" to RoutingRuleSet(name = "t", buckets = b, globalProxy = gp))
                }
            }
        }
    }

    private val targets =
        listOf<OverrideTarget.Resolved>(
            OverrideTarget.ViaOutbound("proxy-auto"),
            OverrideTarget.ViaBalancer("Auto_Balancer"),
        )

    private fun rulesOf(
        routing: RoutingRuleSet?,
        target: OverrideTarget.Resolved,
        dns: DnsPlan?,
    ): List<JsonObject> {
        val json = XrayConfigGenerator.overrideBlocks(settings.copy(routing = routing, dns = dns), target).routingJson
        return ((Json.parseToJsonElement(json) as JsonObject)["rules"] as JsonArray).map { it as JsonObject }
    }

    /** A rule that matches everything a TUN carries and narrows on nothing else. */
    private fun JsonObject.isUnconditioned(): Boolean {
        val network = this["network"]?.jsonPrimitive?.content?.split(",")?.map { it.trim() }?.toSet()
        val narrowing = keys - setOf("type", "network", "outboundTag", "balancerTag")
        return network == setOf("tcp", "udp") && narrowing.isEmpty()
    }

    private fun JsonObject.namesTarget(target: OverrideTarget.Resolved): Boolean =
        when (target) {
            is OverrideTarget.ViaBalancer -> this["balancerTag"]?.jsonPrimitive?.content == target.tag
            is OverrideTarget.ViaOutbound -> this["outboundTag"]?.jsonPrimitive?.content == target.tag
        }

    /**
     * The property the fallthrough defect violated: where unmatched traffic goes
     * is stated by a rule, never left to the order of the config's `outbounds`.
     *
     * Stated as "the last rule is unconditioned" rather than "a catch-all exists
     * somewhere", because Xray takes the first match — a catch-all anywhere but
     * last would shadow the rules behind it instead of backstopping them.
     */
    @Test
    fun `every override ends with an unconditioned rule, whatever the rule set`() {
        ruleSets().forEach { (label, set) ->
            targets.forEach { target ->
                listOf(null, dnsPlan).forEach { dns ->
                    val rules = rulesOf(set, target, dns)
                    withClue(label, target, dns) { rules.isNotEmpty() shouldBe true }
                    withClue(label, target, dns) { rules.last().isUnconditioned() shouldBe true }
                }
            }
        }
    }

    /**
     * And that terminal rule names the resolved target — except when the rule set
     * asks for the opposite, which is the whole meaning of `globalProxy = false`.
     */
    @Test
    fun `the terminal rule names the target unless globalProxy says otherwise`() {
        ruleSets().forEach { (label, set) ->
            targets.forEach { target ->
                listOf(null, dnsPlan).forEach { dns ->
                    val last = rulesOf(set, target, dns).last()
                    val expectDirect = set?.globalProxy == false
                    withClue(label, target, dns) {
                        if (expectDirect) {
                            last["outboundTag"]?.jsonPrimitive?.content shouldBe "direct"
                        } else {
                            last.namesTarget(target) shouldBe true
                        }
                    }
                }
            }
        }
    }

    /**
     * Exactly one, too. A second unconditioned rule is dead code at best — Xray
     * never reaches it — and at worst hides which one is actually deciding.
     */
    @Test
    fun `an override never emits two unconditioned rules`() {
        ruleSets().forEach { (label, set) ->
            targets.forEach { target ->
                listOf(null, dnsPlan).forEach { dns ->
                    val count = rulesOf(set, target, dns).count { it.isUnconditioned() }
                    withClue(label, target, dns) { count shouldBe 1 }
                }
            }
        }
    }

    /**
     * §5.2: the port-53 hijack has to be reached, and a rule matching everything
     * sitting in front of it would mean it never is.
     */
    @Test
    fun `the dns hijack always precedes the terminal rule`() {
        ruleSets().forEach { (label, set) ->
            targets.forEach { target ->
                val rules = rulesOf(set, target, dnsPlan)
                val hijack = rules.indexOfFirst { it["port"]?.jsonPrimitive?.content == "53" }
                withClue(label, target, dnsPlan) { (hijack >= 0) shouldBe true }
                withClue(label, target, dnsPlan) { (hijack < rules.lastIndex) shouldBe true }
            }
        }
    }

    private fun withClue(
        label: String,
        target: OverrideTarget.Resolved,
        dns: DnsPlan?,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: AssertionError) {
            // §5.6: the label and target are this test's own literals, never config text.
            throw AssertionError("[$label | $target | dns=${dns != null}] ${e.message}", e)
        }
    }
}
