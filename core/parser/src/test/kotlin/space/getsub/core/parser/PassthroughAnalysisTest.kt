// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class PassthroughAnalysisTest {
    private val ordinary =
        """
        {
          "outbounds": [
            { "tag": "proxy", "protocol": "vless" },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    @Test
    fun `an ordinary single-server config is eligible with no blocker`() {
        val analysis = analysePassthrough(ordinary)

        analysis.rejection shouldBe null
        analysis.overrideBlocker shouldBe null
        analysis.isBalancer shouldBe false
        analysis.serverOutboundCount shouldBe 1
        analysis.outboundTags shouldContainExactly listOf("proxy", "direct", "block")
    }

    @Test
    fun `text that is not JSON is rejected`() {
        analysePassthrough("vless://not-json").rejection shouldBe PassthroughRejection.NotJson
    }

    @Test
    fun `a config with no outbounds array is rejected`() {
        analysePassthrough("""{ "inbounds": [] }""").rejection shouldBe PassthroughRejection.NoOutbounds
    }

    // Several server outbounds and no balancer: the rows would all carry the same
    // document, so "run this row as written" would ignore which row was tapped.
    @Test
    fun `several server outbounds without a balancer are rejected`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "proxy-2", "protocol": "vless" }
              ]
            }
            """.trimIndent()

        analysePassthrough(json).rejection shouldBe PassthroughRejection.SeveralServers
    }

    // Research §5b: the target panel's auto entry. Several vless outbounds ARE
    // one logical entry when a balancer selects among them.
    @Test
    fun `several server outbounds with a balancer are one balancer entry`() {
        val json =
            """
            {
              "routing": {
                "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"] } ],
                "rules": [ { "network": "tcp,udp", "balancerTag": "Auto_Balancer" } ]
              },
              "outbounds": [
                { "tag": "proxy-auto", "protocol": "vless" },
                { "tag": "proxy-auto-2", "protocol": "vless" },
                { "tag": "direct", "protocol": "freedom" }
              ]
            }
            """.trimIndent()
        val analysis = analysePassthrough(json)

        analysis.rejection shouldBe null
        analysis.isBalancer shouldBe true
        analysis.serverOutboundCount shouldBe 2
    }

    // Research §5b.1: tagPrefix "proxy-auto" with addVirtualHostAsOutbound unset
    // leaves NO outbound tagged exactly `proxy`. The config runs fine; only our
    // override branch, whose rules name `proxy`, cannot be applied to it.
    @Test
    fun `a config with no proxy tag is eligible but blocks the override branch`() {
        val json =
            """
            {
              "routing": { "balancers": [ { "tag": "B", "selector": ["proxy"] } ] },
              "outbounds": [
                { "tag": "proxy-auto", "protocol": "vless" },
                { "tag": "proxy-auto-2", "protocol": "vless" }
              ]
            }
            """.trimIndent()
        val analysis = analysePassthrough(json)

        analysis.rejection shouldBe null
        analysis.overrideBlocker shouldBe OverrideBlocker.NoProxyTag
    }

    @Test
    fun `duplicate or blank outbound tags block the override branch`() {
        val duplicate =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "proxy", "protocol": "freedom" }
              ]
            }
            """.trimIndent()
        val blank =
            """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "protocol": "freedom" }
              ]
            }
            """.trimIndent()

        analysePassthrough(duplicate).overrideBlocker shouldBe OverrideBlocker.AmbiguousOutboundTags
        analysePassthrough(blank).overrideBlocker shouldBe OverrideBlocker.AmbiguousOutboundTags
    }

    // Research §5b.5: sniffing decides whether the config's own domain rules can
    // ever match. Advisory, never a rejection — it is the config's own choice.
    @Test
    fun `domain rules with sniffing off raise an advisory`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["domain:ru"], "outboundTag": "direct" } ] },
              "inbounds": [ { "tag": "socks", "protocol": "socks" } ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" },
                             { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.SniffingCannotServeOwnRules)
    }

    @Test
    fun `fakedns without a matching destOverride raises an advisory`() {
        val json =
            """
            {
              "dns": { "servers": ["fakedns", "8.8.8.8"] },
              "inbounds": [
                {
                  "tag": "socks",
                  "protocol": "socks",
                  "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
                }
              ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.FakeDnsWithoutSniffingOverride)
    }

    // Device record F9: the target panel's own balancer entry, reduced to the defect.
    @Test
    fun `a rule naming an outbound that does not exist is an advisory`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": { "rules": [ { "type": "field", "outboundTag": "proxy" } ] }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.DanglingRoutingReference)
    }

    @Test
    fun `a balancer fallbackTag naming an outbound that does not exist is an advisory`() {
        // The target panel's own balancer entry, reduced to the defect (device record F9).
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" },
                             { "tag": "proxy-auto-2", "protocol": "vless" } ],
              "routing": {
                "rules": [ { "type": "field", "network": "tcp,udp", "balancerTag": "Auto_Balancer" } ],
                "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"], "fallbackTag": "proxy" } ]
              }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.DanglingRoutingReference)
    }

    @Test
    fun `a selector prefix that matches no tag exactly is not a dangling reference`() {
        // selector is a PREFIX match: "proxy" legitimately selects proxy-auto*.
        // Flagging it would report a defect that is not there.
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" },
                             { "tag": "direct", "protocol": "freedom" } ],
              "routing": {
                "rules": [ { "type": "field", "network": "tcp,udp", "balancerTag": "Auto_Balancer" } ],
                "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"], "fallbackTag": "direct" } ]
              }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly emptyList()
    }

    @Test
    fun `a rule naming a balancer that does not exist is an advisory`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ],
              "routing": { "rules": [ { "type": "field", "balancerTag": "Nope" } ] }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.DanglingRoutingReference)
    }

    @Test
    fun `a config whose every reference resolves carries no dangling advisory`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy", "protocol": "vless" },
                             { "tag": "direct", "protocol": "freedom" } ],
              "routing": { "rules": [ { "type": "field", "outboundTag": "direct" },
                                      { "type": "field", "outboundTag": "proxy" } ] }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly emptyList()
    }

    // Fix round 1, Minor 4: an outbound with no `tag` seeds the outbound-tag namespace with ""
    // (analyseOutbounds' orEmpty()). A rule's own equally blank "outboundTag": "" must not
    // resolve against that placeholder — a blank reference is never a real one.
    @Test
    fun `a rule with a blank outboundTag is a dangling reference, not a match against an untagged outbound`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy", "protocol": "vless" },
                             { "protocol": "freedom" } ],
              "routing": { "rules": [ { "type": "field", "outboundTag": "" } ] }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.DanglingRoutingReference)
    }

    // Fix round 1, Minor 3 (promoted): ARCHITECTURE.md's "Passthrough execution" section (line
    // 331) lists `reverse` among the blocks RawConfigComposer deliberately preserves as opaque —
    // such a config reaches the core through passthrough. In xray's reverse-proxy shape, a rule's
    // `outboundTag` can legitimately name a `reverse` bridge/portal tag outside `outbounds`; this
    // analyser has no model of that namespace and no upstream citation to widen into it (§10.5),
    // so it suppresses the whole check rather than risk telling the user a working config is
    // broken (§10.4) — the same reasoning the `selector` carve-out rests on.
    @Test
    fun `a config with a reverse block raises no dangling advisory even with an unresolved outboundTag`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ],
              "reverse": { "bridges": [ { "tag": "bridge", "domain": "test.local" } ] },
              "routing": { "rules": [ { "type": "field", "outboundTag": "bridge" } ] }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly emptyList()
    }

    // Minor 4: `root["reverse"]` is `JsonNull` — a non-null `JsonElement` — for an explicit
    // `"reverse": null`, so a bare `!= null` check would suppress the whole dangling-reference
    // check for this shape too. It must not: there is no reverse block here, so the check stays
    // live and the dangling outboundTag below is still reported.
    @Test
    fun `an explicit reverse null does not suppress the dangling reference check`() {
        val json =
            """
            {
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ],
              "reverse": null,
              "routing": { "rules": [ { "type": "field", "outboundTag": "missing" } ] }
            }
            """.trimIndent()

        analysePassthrough(json).advisories shouldContainExactly
            listOf(PassthroughAdvisory.DanglingRoutingReference)
    }

    // §7: this analyser is on the never-throw side of the parser boundary.
    @Test
    fun `malformed shapes are reported, never thrown`() {
        analysePassthrough("""{ "outbounds": "not-an-array" }""").rejection shouldBe
            PassthroughRejection.NoOutbounds
        analysePassthrough("").rejection shouldBe PassthroughRejection.NotJson
    }

    // Task 8: CoreRejected is xray-core's own verdict, written by `:service` after `testXray` —
    // never this analyser's, which is structural only and never calls the core. One sample per
    // branch of analysePassthrough's own logic, so a future edit that starts returning
    // CoreRejected from in here gets caught rather than silently blurring the boundary.
    @Test
    fun `the structural analyser never returns CoreRejected — only the core can`() {
        val samples =
            listOf(
                ordinary,
                "vless://not-json",
                """{ "inbounds": [] }""",
                """
                {
                  "outbounds": [
                    { "tag": "proxy", "protocol": "vless" },
                    { "tag": "proxy-2", "protocol": "vless" }
                  ]
                }
                """.trimIndent(),
                """
                {
                  "routing": { "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"] } ] },
                  "outbounds": [
                    { "tag": "proxy-auto", "protocol": "vless" },
                    { "tag": "proxy-auto-2", "protocol": "vless" }
                  ]
                }
                """.trimIndent(),
                "",
            )

        samples.forEach { json ->
            analysePassthrough(json).rejection shouldNotBe PassthroughRejection.CoreRejected
        }
    }
}
