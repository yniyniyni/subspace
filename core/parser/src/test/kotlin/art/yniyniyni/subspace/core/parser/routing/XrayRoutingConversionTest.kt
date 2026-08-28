// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser.routing

import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class XrayRoutingConversionTest {
    private fun convert(json: String) = convertXrayRouting(json, name = "Pasted config")

    @Test
    fun `a domain rule becomes a bucket entry`() {
        val json =
            """
            {
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [ { "domain": ["domain:ru", "geosite:category-ru"], "outboundTag": "direct" } ]
              },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        val result = convert(json)!!

        result.profile.bucket(RouteOutcome.DIRECT).sites shouldContainExactly
            listOf("domain:ru", "geosite:category-ru")
        result.profile.domainStrategy shouldBe DomainStrategy.AS_IS
        result.drops shouldBe emptyMap()
    }

    // Outcome comes from the outbound's PROTOCOL. The target panel tags its
    // servers proxy-auto*, so a tag-based mapping would fail on the real config.
    @Test
    fun `outcome is read from the referenced outbound's protocol, not its tag`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["example.com"], "outboundTag": "proxy-auto-3" } ] },
              "outbounds": [ { "tag": "proxy-auto-3", "protocol": "vless" } ]
            }
            """.trimIndent()

        convert(json)!!.profile.bucket(RouteOutcome.PROXY).sites shouldContainExactly listOf("example.com")
    }

    // The specific inversion a tag-based mapping would cause: a `freedom` outbound
    // tagged "proxy" must still resolve to DIRECT, not PROXY — deciding by tag
    // here would silently send this traffic through the tunnel's proxy instead
    // of bypassing it, the opposite of what the config's author wrote.
    @Test
    fun `a freedom outbound tagged 'proxy' still resolves to DIRECT, not PROXY`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["example.com"], "outboundTag": "proxy" } ] },
              "outbounds": [ { "tag": "proxy", "protocol": "freedom" } ]
            }
            """.trimIndent()

        val result = convert(json)!!

        result.profile.bucket(RouteOutcome.DIRECT).sites shouldContainExactly listOf("example.com")
        result.profile.bucket(RouteOutcome.PROXY).sites shouldContainExactly emptyList()
    }

    @Test
    fun `an ip rule becomes an ip bucket entry`() {
        val json =
            """
            {
              "routing": { "rules": [ { "ip": ["10.0.0.0/8"], "outboundTag": "block" } ] },
              "outbounds": [ { "tag": "block", "protocol": "blackhole" } ]
            }
            """.trimIndent()

        convert(json)!!.profile.bucket(RouteOutcome.BLOCK).ips shouldContainExactly listOf("10.0.0.0/8")
    }

    // The target panel's DNS rules: ip + port. RuleBucket cannot express port.
    @Test
    fun `a rule keyed on port is dropped and counted`() {
        val json =
            """
            {
              "routing": {
                "rules": [
                  { "ip": ["8.8.8.8"], "port": "53", "outboundTag": "direct" },
                  { "domain": ["example.com"], "outboundTag": "direct" }
                ]
              },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        val result = convert(json)!!

        result.drops[ConversionDrop.UnsupportedMatcher] shouldBe 1
        result.profile.bucket(RouteOutcome.DIRECT).ips shouldContainExactly emptyList()
        result.profile.bucket(RouteOutcome.DIRECT).sites shouldContainExactly listOf("example.com")
    }

    @Test
    fun `a balancerTag rule is dropped and counted`() {
        val json =
            """
            {
              "routing": {
                "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"] } ],
                "rules": [ { "network": "tcp,udp", "balancerTag": "Auto_Balancer" } ]
              },
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ]
            }
            """.trimIndent()

        convert(json)!!.drops[ConversionDrop.BalancerRule] shouldBe 1
    }

    // Xray needs BOTH to match; two buckets would give EITHER. More permissive
    // than the author wrote, so it is dropped rather than approximated.
    @Test
    fun `a rule carrying both domain and ip is dropped`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["a.com"], "ip": ["1.2.3.4"], "outboundTag": "direct" } ] },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        val result = convert(json)!!

        result.drops[ConversionDrop.DomainAndIpInOneRule] shouldBe 1
        result.profile.entryCount shouldBe 0
    }

    @Test
    fun `a rule naming an outbound the config does not define is dropped`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["a.com"], "outboundTag": "ghost" } ] },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        convert(json)!!.drops[ConversionDrop.UnknownOutbound] shouldBe 1
    }

    // routeOrder is a permutation; proxy → direct → proxy is not one.
    @Test
    fun `an order no permutation can express is reported`() {
        val json =
            """
            {
              "routing": {
                "rules": [
                  { "domain": ["a.com"], "outboundTag": "proxy" },
                  { "domain": ["b.com"], "outboundTag": "direct" },
                  { "domain": ["c.com"], "outboundTag": "proxy" }
                ]
              },
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "direct", "protocol": "freedom" }
              ]
            }
            """.trimIndent()

        val result = convert(json)!!

        result.drops[ConversionDrop.OrderNotRepresentable] shouldBe 1
        // Entries are still kept — order is approximated, contents are not lost.
        result.profile.bucket(RouteOutcome.PROXY).sites shouldContainExactly listOf("a.com", "c.com")
        result.profile.routeOrder.first() shouldBe RouteOutcome.PROXY
    }

    // Research §5b.6: ProfileDns.hosts is Map<String, String>.
    @Test
    fun `a hosts entry mapping to several addresses is dropped`() {
        val json =
            """
            {
              "dns": {
                "hosts": {
                  "dns.google": ["8.8.8.8", "8.8.4.4"],
                  "domain:googleapis.cn": "googleapis.com"
                },
                "servers": ["8.8.8.8"]
              },
              "routing": { "rules": [ { "domain": ["a.com"], "outboundTag": "direct" } ] },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        val result = convert(json)!!

        result.drops[ConversionDrop.MultiAddressHost] shouldBe 1
        result.profile.dns!!.hosts shouldBe mapOf("domain:googleapis.cn" to "googleapis.com")
    }

    @Test
    fun `a config with no routing rules converts to nothing`() {
        convert("""{ "outbounds": [ { "tag": "proxy", "protocol": "vless" } ] }""") shouldBe null
    }

    @Test
    fun `malformed input returns null rather than throwing`() {
        convert("not json") shouldBe null
    }

    @Test
    fun `the profile carries the supplied name`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["a.com"], "outboundTag": "direct" } ] },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        convert(json)!!.profile.name shouldBe "Pasted config"
    }

    // §5.6: entries are browsing data and must not reach a log line.
    @Test
    fun `toString redacts entries`() {
        val json =
            """
            {
              "routing": { "rules": [ { "domain": ["secret.example"], "outboundTag": "direct" } ] },
              "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
            }
            """.trimIndent()

        convert(json).toString().contains("secret.example") shouldBe false
        convert(json) shouldNotBe null
    }
}
