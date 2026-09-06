// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The precedence rule from design §3.3, one case per branch.
 *
 * `reserved` is what the override branch would append — `TunnelService` supplies
 * the real set, which is `direct`/`block` plus `dns-out` only when a DNS plan is
 * present (design §3.4).
 */
class OverrideTargetTest {
    private val reserved = setOf("direct", "block", "dns-out")

    private fun resolve(
        json: String,
        reservedTags: Set<String> = reserved,
    ): OverrideTarget = resolveOverrideTarget(analysePassthrough(json), reservedTags)

    // --- Step 2: the single-server branch --------------------------------

    @Test
    fun `a lone server outbound is the target, whatever it is called`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy-auto", "protocol": "vless" },
                { "tag": "direct", "protocol": "freedom" }
              ]
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaOutbound("proxy-auto")
    }

    @Test
    fun `a lone server tagged proxy resolves to proxy, as today`() {
        val json =
            """
            { "outbounds": [ { "tag": "proxy", "protocol": "vless" } ] }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaOutbound("proxy")
    }

    @Test
    fun `an untagged lone server cannot be named`() {
        val json = """{ "outbounds": [ { "protocol": "vless" } ] }"""

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.NoResolvableTarget)
    }

    @Test
    fun `a config with only infrastructure outbounds has no target`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.NoResolvableTarget)
    }

    // --- Gate 0: ambiguity ------------------------------------------------

    @Test
    fun `two outbounds sharing a tag are ambiguous`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "proxy", "protocol": "vless" }
              ]
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.AmbiguousOutboundTags)
    }

    @Test
    fun `an untagged outbound beside a tagged server is not ambiguity`() {
        // This shape runs today and must keep running: a blank tag is never a
        // candidate, so two of them cannot collide (design §3.3).
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "protocol": "freedom" },
                { "protocol": "blackhole" }
              ]
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaOutbound("proxy")
    }

    // --- Step 1: the balancer branch --------------------------------------

    @Test
    fun `a live balancer is the target and selector matching is by prefix`() {
        // The target panel's shape: outbounds tagged proxy-auto*, selector ["proxy"].
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy-auto", "protocol": "vless" },
                { "tag": "proxy-auto-2", "protocol": "vless" }
              ],
              "routing": { "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaBalancer("Auto_Balancer")
    }

    @Test
    fun `a balancer selecting nothing is refused rather than named`() {
        // A6: the core accepts this and silently drops. It is the one case its
        // own build-time check does not cover for us.
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["nothing-matches"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.BalancerSelectsNothing)
    }

    @Test
    fun `a balancer selecting only infrastructure outbounds is not live`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy-auto", "protocol": "vless" },
                { "tag": "escape", "protocol": "freedom" }
              ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["escape"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.BalancerSelectsNothing)
    }

    @Test
    fun `a balancer with no usable tag is unnameable, not empty`() {
        // Its selector matches `proxy-auto` perfectly well. The defect is that no
        // rule can name it — the config's own rules included — so reporting it as
        // "selects nothing" would send the user looking at the wrong half.
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": { "balancers": [ { "selector": ["proxy"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.BalancerHasNoTag)
    }

    @Test
    fun `one nameable balancer among unnameable ones is the one reported on`() {
        // `BalancerHasNoTag` is only for the case where *nothing* can be named.
        // Once one balancer can be, the question becomes what that one selects.
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": {
                "balancers": [ { "selector": ["proxy"] }, { "tag": "B", "selector": ["nope"] } ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.BalancerSelectsNothing)
    }

    @Test
    fun `a balancer with no selector selects nothing`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": { "balancers": [ { "tag": "B" } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.BalancerSelectsNothing)
    }

    // --- §3.4: the collision guard ----------------------------------------

    @Test
    fun `a selector that would capture an appended outbound is refused`() {
        // "d" prefix-matches our appended `direct`, which would pull a freedom
        // outbound into the user's balancer and send proxied traffic out
        // unproxied — §5.2, silently.
        val json =
            """
            {
              "outbounds": [ { "tag": "dproxy", "protocol": "vless" } ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["d"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.TargetTagCollision)
    }

    @Test
    fun `a balancer that also selects the config's own freedom outbound is refused`() {
        // Branch review C1. Liveness only asks whether a selector matches at least
        // one *server* outbound, and `serverOutboundTags()` filters the rest out —
        // so without this the balancer resolves, our `dns-module` catch-all names
        // it, and the core spreads resolver queries across a `freedom` member that
        // leaves the tunnel. §5.2, silently.
        val json =
            """
            {
              "outbounds": [
                { "tag": "exit-1", "protocol": "vless" },
                { "tag": "exit-local", "protocol": "freedom" }
              ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["exit"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.TargetTagCollision)
    }

    @Test
    fun `a balancer that also selects a blackhole outbound is refused`() {
        // The same hazard pointing the other way: instead of leaving the tunnel, a
        // share of proxied traffic is dropped, equally silently.
        val json =
            """
            {
              "outbounds": [
                { "tag": "exit-1", "protocol": "vless" },
                { "tag": "exit-drop", "protocol": "blackhole" }
              ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["exit"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.TargetTagCollision)
    }

    @Test
    fun `a balancer whose selector stops short of the infrastructure outbound still resolves`() {
        // The guard must not refuse every config that happens to own a freedom
        // outbound — only one the balancer would actually capture.
        val json =
            """
            {
              "outbounds": [
                { "tag": "exit-1", "protocol": "vless" },
                { "tag": "local", "protocol": "freedom" }
              ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["exit"] } ] }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaBalancer("B")
    }

    @Test
    fun `catch-alls naming a dead and a live balancer are undecidable`() {
        // Branch review M2. Design §3.3: "Zero, or two or more distinct balancers,
        // is Unresolvable". Filtering the references by liveness first would rescue
        // this to `EU` — a guess about which of the config's own catch-alls fires,
        // which is the evaluation-order question §3.3 refuses to answer.
        val json =
            """
            {
              "outbounds": [
                { "tag": "eu-1", "protocol": "vless" },
                { "tag": "us-1", "protocol": "vless" }
              ],
              "routing": {
                "rules": [
                  { "type": "field", "network": "tcp,udp", "balancerTag": "DEAD" },
                  { "type": "field", "network": "tcp,udp", "balancerTag": "EU" }
                ],
                "balancers": [
                  { "tag": "EU", "selector": ["eu"] },
                  { "tag": "US", "selector": ["us"] },
                  { "tag": "DEAD", "selector": ["nope"] }
                ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.SeveralBalancers)
    }

    @Test
    fun `a dns-out collision is refused only when a dns plan is present`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "dns-outer", "protocol": "vless" } ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["dns-out"] } ] }
            }
            """.trimIndent()

        resolve(json, setOf("direct", "block", "dns-out")) shouldBe
            OverrideTarget.Unresolvable(OverrideBlocker.TargetTagCollision)
        resolve(json, setOf("direct", "block")) shouldBe OverrideTarget.ViaBalancer("B")
    }

    // --- Several balancers -------------------------------------------------

    @Test
    fun `two live balancers are disambiguated by the config's own catch-all`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "eu-1", "protocol": "vless" },
                { "tag": "us-1", "protocol": "vless" }
              ],
              "routing": {
                "rules": [
                  { "type": "field", "domain": ["example.com"], "balancerTag": "EU" },
                  { "type": "field", "network": "tcp,udp", "balancerTag": "US" }
                ],
                "balancers": [
                  { "tag": "EU", "selector": ["eu"] },
                  { "tag": "US", "selector": ["us"] }
                ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaBalancer("US")
    }

    @Test
    fun `two live balancers with no catch-all rule are undecidable`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "eu-1", "protocol": "vless" },
                { "tag": "us-1", "protocol": "vless" }
              ],
              "routing": {
                "balancers": [
                  { "tag": "EU", "selector": ["eu"] },
                  { "tag": "US", "selector": ["us"] }
                ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.SeveralBalancers)
    }

    @Test
    fun `two catch-all rules naming different balancers are undecidable`() {
        // Xray takes the first MATCH, so among two rules that both match
        // everything the first fires and the second is dead. Picking either is a
        // claim about evaluation order this project has not measured (design
        // §3.3), so it refuses instead.
        val json =
            """
            {
              "outbounds": [
                { "tag": "eu-1", "protocol": "vless" },
                { "tag": "us-1", "protocol": "vless" }
              ],
              "routing": {
                "rules": [
                  { "type": "field", "network": "tcp,udp", "balancerTag": "EU" },
                  { "type": "field", "network": "tcp,udp", "balancerTag": "US" }
                ],
                "balancers": [
                  { "tag": "EU", "selector": ["eu"] },
                  { "tag": "US", "selector": ["us"] }
                ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.SeveralBalancers)
    }

    @Test
    fun `repeated catch-all rules naming the same balancer still resolve`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "eu-1", "protocol": "vless" },
                { "tag": "us-1", "protocol": "vless" }
              ],
              "routing": {
                "rules": [
                  { "type": "field", "network": "tcp,udp", "balancerTag": "EU" },
                  { "type": "field", "network": "tcp,udp", "balancerTag": "EU" }
                ],
                "balancers": [
                  { "tag": "EU", "selector": ["eu"] },
                  { "tag": "US", "selector": ["us"] }
                ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.ViaBalancer("EU")
    }

    @Test
    fun `a catch-all naming a balancer that selects nothing does not rescue it`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "eu-1", "protocol": "vless" },
                { "tag": "us-1", "protocol": "vless" }
              ],
              "routing": {
                "rules": [ { "type": "field", "network": "tcp,udp", "balancerTag": "DEAD" } ],
                "balancers": [
                  { "tag": "EU", "selector": ["eu"] },
                  { "tag": "US", "selector": ["us"] },
                  { "tag": "DEAD", "selector": ["nope"] }
                ]
              }
            }
            """.trimIndent()

        resolve(json) shouldBe OverrideTarget.Unresolvable(OverrideBlocker.SeveralBalancers)
    }

    // --- Malformed input ---------------------------------------------------

    @Test
    fun `text that is not JSON has no target and does not throw`() {
        resolve("vless://not-json") shouldBe
            OverrideTarget.Unresolvable(OverrideBlocker.NoResolvableTarget)
    }
}
