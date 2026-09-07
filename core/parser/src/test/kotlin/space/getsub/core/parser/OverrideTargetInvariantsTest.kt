// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * One property, stated once, over every way a non-server outbound can be reached.
 *
 * `OverrideTarget` refuses several shapes, and each refusal arrived as its own
 * fix for its own reported defect: a `selector` capturing the config's own
 * `freedom` outbound, a `selector` capturing one of the tags the override
 * reserves, a `fallbackTag` pointing at a `freedom` outbound. They are not three
 * rules. They are one rule — **a resolved target must not be able to put traffic
 * on an outbound that does not reach a server** — that was discovered three
 * times because it had been written down as examples instead of as an invariant.
 *
 * The hazard is §5.2 in every case: a `freedom` member sends proxied traffic,
 * including the `dns-module` catch-all's, outside the tunnel; a `blackhole` one
 * drops it. Both silently.
 *
 * A new route to a non-server outbound — a future balancer field, a new
 * strategy, another emitter — fails here without anyone having to remember the
 * three earlier bugs.
 */
class OverrideTargetInvariantsTest {
    private val reserved = setOf("direct", "block", "dns-out")

    /**
     * Every outbound the resolved target could put traffic on, computed from the
     * document the way the core would read it.
     *
     * `ViaOutbound` names exactly one. `ViaBalancer` reaches every outbound its
     * `selector` prefix-matches — that prefix semantics is measured, see
     * `2026-09-01-balancer-tag-binding.md` — plus its `fallbackTag`, which is an
     * exact name.
     */
    private fun reachableOutbounds(
        analysis: PassthroughAnalysis,
        target: OverrideTarget.Resolved,
    ): Set<String> =
        when (target) {
            is OverrideTarget.ViaOutbound -> setOf(target.tag)
            is OverrideTarget.ViaBalancer -> {
                val spec = analysis.balancers.first { it.tag == target.tag }
                val selected =
                    analysis.outboundProtocolsByTag.keys.filter { tag ->
                        spec.selector.any { prefix -> tag.startsWith(prefix) }
                    }
                (selected + listOfNotNull(spec.fallbackTag)).toSet()
            }
        }

    /** Configs that each offer some route from a resolved target to a non-server outbound. */
    private fun hazardousConfigs(): List<Pair<String, String>> =
        listOf(
            "selector captures the config's own freedom outbound" to
                """
                {
                  "outbounds": [
                    { "tag": "exit-1", "protocol": "vless" },
                    { "tag": "exit-local", "protocol": "freedom" }
                  ],
                  "routing": { "balancers": [ { "tag": "B", "selector": ["exit"] } ] }
                }
                """.trimIndent(),
            "selector captures the config's own blackhole outbound" to
                """
                {
                  "outbounds": [
                    { "tag": "exit-1", "protocol": "vless" },
                    { "tag": "exit-drop", "protocol": "blackhole" }
                  ],
                  "routing": { "balancers": [ { "tag": "B", "selector": ["exit"] } ] }
                }
                """.trimIndent(),
            "selector captures a tag the override reserves" to
                """
                {
                  "outbounds": [ { "tag": "dproxy", "protocol": "vless" } ],
                  "routing": { "balancers": [ { "tag": "B", "selector": ["d"] } ] }
                }
                """.trimIndent(),
            "fallbackTag points at a freedom outbound" to
                """
                {
                  "outbounds": [
                    { "tag": "eu-1", "protocol": "vless" },
                    { "tag": "local", "protocol": "freedom" }
                  ],
                  "routing": {
                    "balancers": [ { "tag": "B", "selector": ["eu-"], "fallbackTag": "local" } ]
                  }
                }
                """.trimIndent(),
            "fallbackTag points at a blackhole outbound" to
                """
                {
                  "outbounds": [
                    { "tag": "eu-1", "protocol": "vless" },
                    { "tag": "sink", "protocol": "blackhole" }
                  ],
                  "routing": {
                    "balancers": [ { "tag": "B", "selector": ["eu-"], "fallbackTag": "sink" } ]
                  }
                }
                """.trimIndent(),
            "a lone freedom outbound is not a server to name" to
                """{ "outbounds": [ { "tag": "proxy", "protocol": "freedom" } ] }""",
        )

    /** Shapes that are safe and must keep resolving — the guard must not over-refuse. */
    private fun safeConfigs(): List<Pair<String, String>> =
        listOf(
            "balancer over servers only, untouched infrastructure beside it" to
                """
                {
                  "outbounds": [
                    { "tag": "eu-1", "protocol": "vless" },
                    { "tag": "eu-2", "protocol": "vless" },
                    { "tag": "local", "protocol": "freedom" },
                    { "tag": "sink", "protocol": "blackhole" }
                  ],
                  "routing": {
                    "balancers": [ { "tag": "B", "selector": ["eu-"], "fallbackTag": "eu-2" } ]
                  }
                }
                """.trimIndent(),
            "single server beside untagged infrastructure" to
                """
                {
                  "outbounds": [
                    { "tag": "proxy", "protocol": "vless" },
                    { "protocol": "freedom" }
                  ]
                }
                """.trimIndent(),
        )

    @Test
    fun `no resolved target can reach an outbound that is not a server`() {
        (hazardousConfigs() + safeConfigs()).forEach { (label, json) ->
            val analysis = analysePassthrough(json)
            val target = resolveOverrideTarget(analysis, reserved)
            if (target !is OverrideTarget.Resolved) return@forEach

            val reachable = reachableOutbounds(analysis, target)
            val offending =
                reachable.filter { tag ->
                    tag in reserved || analysis.outboundProtocolsByTag[tag] in NON_SERVER_PROTOCOLS
                }
            // §5.6: `label` is this test's own text; no config content is surfaced.
            check(offending.isEmpty()) {
                "[$label] resolved to a target that can reach a non-server outbound"
            }
        }
    }

    @Test
    fun `every hazardous shape is refused rather than resolved`() {
        hazardousConfigs().forEach { (label, json) ->
            val target = resolveOverrideTarget(analysePassthrough(json), reserved)
            check(target is OverrideTarget.Unresolvable) { "[$label] resolved when it should have refused" }
        }
    }

    @Test
    fun `the guard does not over-refuse a config whose infrastructure it never selects`() {
        safeConfigs().forEach { (label, json) ->
            val target = resolveOverrideTarget(analysePassthrough(json), reserved)
            check(target is OverrideTarget.Resolved) { "[$label] refused a config that is safe to resolve" }
        }
    }

    /**
     * Refusal is a decision about the app's override, never about the file. A
     * config we will not write rules against still runs as written under pure
     * passthrough, which is the whole point of that branch.
     */
    @Test
    fun `refusing to resolve never makes a config ineligible`() {
        hazardousConfigs().forEach { (label, json) ->
            check(analysePassthrough(json).rejection == null) {
                "[$label] a resolution refusal leaked into eligibility"
            }
        }
    }

    /** One shape per [OverrideBlocker] member, for the coverage test below. */
    private val shapeByReason: Map<OverrideBlocker, String> =
        mapOf(
            OverrideBlocker.AmbiguousOutboundTags to
                """
                {
                  "outbounds": [
                    { "tag": "proxy", "protocol": "vless" },
                    { "tag": "proxy", "protocol": "vless" }
                  ]
                }
                """.trimIndent(),
            OverrideBlocker.NoResolvableTarget to
                """{ "outbounds": [ { "tag": "direct", "protocol": "freedom" } ] }""",
            OverrideBlocker.BalancerHasNoTag to
                """
                {
                  "outbounds": [ { "tag": "eu-1", "protocol": "vless" } ],
                  "routing": { "balancers": [ { "selector": ["eu-"] } ] }
                }
                """.trimIndent(),
            OverrideBlocker.BalancerSelectsNothing to
                """
                {
                  "outbounds": [ { "tag": "eu-1", "protocol": "vless" } ],
                  "routing": { "balancers": [ { "tag": "B", "selector": ["nope"] } ] }
                }
                """.trimIndent(),
            OverrideBlocker.SeveralBalancers to
                """
                {
                  "outbounds": [
                    { "tag": "eu-1", "protocol": "vless" },
                    { "tag": "us-1", "protocol": "vless" }
                  ],
                  "routing": {
                    "balancers": [
                      { "tag": "EU", "selector": ["eu-"] },
                      { "tag": "US", "selector": ["us-"] }
                    ]
                  }
                }
                """.trimIndent(),
            OverrideBlocker.TargetTagCollision to
                """
                {
                  "outbounds": [
                    { "tag": "exit-1", "protocol": "vless" },
                    { "tag": "exit-local", "protocol": "freedom" }
                  ],
                  "routing": { "balancers": [ { "tag": "B", "selector": ["exit"] } ] }
                }
                """.trimIndent(),
            OverrideBlocker.BalancerFallbackNotAServer to
                """
                {
                  "outbounds": [
                    { "tag": "eu-1", "protocol": "vless" },
                    { "tag": "local", "protocol": "freedom" }
                  ],
                  "routing": {
                    "balancers": [ { "tag": "B", "selector": ["eu-"], "fallbackTag": "local" } ]
                  }
                }
                """.trimIndent(),
        )

    /**
     * Spec §6 asks for one case per [OverrideBlocker] member. Stated as coverage
     * rather than seven separate tests, so that adding a member without a shape
     * that produces it fails here — a reason nothing can produce is exactly the
     * dead vocabulary `NoProxyTag` was, and §6 records what that cost.
     */
    @Test
    fun `every refusal reason is produced by some real config`() {
        shapeByReason.keys shouldBe OverrideBlocker.entries.toSet()
        shapeByReason.forEach { (reason, json) ->
            val target = resolveOverrideTarget(analysePassthrough(json), reserved)
            check(target == OverrideTarget.Unresolvable(reason)) { "expected $reason, got $target" }
        }
    }

    @Test
    fun `resolution is total — every shape yields a target or a typed reason`() {
        val shapes =
            (hazardousConfigs() + safeConfigs()).map { it.second } +
                listOf("", "   ", "not json at all", "[]", "{}", """{ "outbounds": [] }""")
        shapes.forEach { json ->
            val target = resolveOverrideTarget(analysePassthrough(json), reserved)
            val typed = target is OverrideTarget.Resolved || target is OverrideTarget.Unresolvable
            typed shouldBe true
        }
    }
}
