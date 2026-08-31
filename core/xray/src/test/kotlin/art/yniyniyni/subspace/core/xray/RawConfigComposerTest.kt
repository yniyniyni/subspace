// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class RawConfigComposerTest {
    private val settings =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            httpPort = 41081,
        )

    // Modelled on the target panel's balancer template (research §5b): its own
    // sniffing without quic, a burstObservatory, a balancers array, fixed inbound
    // ports, and loglevel info with no access key.
    private val panelLike =
        """
        {
          "burstObservatory": {
            "pingConfig": { "interval": "2m", "destination": "http://www.gstatic.com/generate_204" },
            "subjectSelector": ["proxy"]
          },
          "dns": { "servers": ["8.8.8.8"], "hosts": { "dns.google": ["8.8.8.8", "8.8.4.4"] } },
          "log": { "loglevel": "info" },
          "routing": {
            "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"] } ],
            "rules": [ { "network": "tcp,udp", "balancerTag": "Auto_Balancer" } ],
            "domainMatcher": "hybrid",
            "domainStrategy": "AsIs"
          },
          "inbounds": [
            {
              "tag": "socks",
              "port": 10808,
              "listen": "127.0.0.1",
              "protocol": "socks",
              "sniffing": { "enabled": true, "routeOnly": false, "destOverride": ["http", "tls"] }
            },
            { "tag": "http", "port": 10809, "listen": "127.0.0.1", "protocol": "http" }
          ],
          "outbounds": [
            { "tag": "proxy-auto", "protocol": "vless" },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ],
          "stats": {},
          "metrics": { "listen": "127.0.0.1:11111" }
        }
        """.trimIndent()

    private fun composed(raw: String = panelLike): JsonObject {
        val result = RawConfigComposer.compose(raw, settings, assetDir = "/data/geo", override = null)
        result.shouldBeOk()
        return Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject
    }

    private fun ComposeResult.shouldBeOk() {
        if (this !is ComposeResult.Ok) error("expected Ok, was $this")
    }

    @Test
    fun `the config's own routing and dns survive untouched`() {
        val out = composed()

        val routing = out["routing"] as JsonObject
        (routing["balancers"] as JsonArray).size shouldBe 1
        (routing["domainMatcher"]!!.jsonPrimitive.content) shouldBe "hybrid"
        ((out["dns"] as JsonObject)["servers"] as JsonArray).size shouldBe 1
    }

    // Spec §7: preserving unknown keys is the point of the milestone.
    @Test
    fun `keys this design does not name are preserved`() {
        val out = composed()

        out["burstObservatory"] shouldBe (Json.parseToJsonElement(panelLike) as JsonObject)["burstObservatory"]
    }

    @Test
    fun `the config's outbounds are untouched`() {
        val out = composed()

        (out["outbounds"] as JsonArray).size shouldBe 3
    }

    // Spec §2: the panel ships fixed ports 10808/10809, which we cannot use.
    @Test
    fun `inbounds are replaced with our allocated loopback pair`() {
        val inbounds = composed()["inbounds"] as JsonArray

        inbounds.size shouldBe 2
        val socks = inbounds[0] as JsonObject
        socks["port"]!!.jsonPrimitive.content shouldBe "41080"
        socks["listen"]!!.jsonPrimitive.content shouldBe "127.0.0.1"
        socks["tag"]!!.jsonPrimitive.content shouldBe "socks"
        val http = inbounds[1] as JsonObject
        http["port"]!!.jsonPrimitive.content shouldBe "41081"
        http["tag"]!!.jsonPrimitive.content shouldBe "http"
    }

    @Test
    fun `routing rules keep matching the original client inbound tags`() {
        val raw =
            """
            {
              "inbounds": [
                { "tag": "client-socks", "protocol": "socks", "port": 10808 },
                { "tag": "client-http", "protocol": "http", "port": 10809 }
              ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ],
              "routing": {
                "rules": [
                  { "inboundTag": ["client-socks"], "outboundTag": "proxy" },
                  { "inboundTag": ["client-http"], "outboundTag": "proxy" }
                ]
              }
            }
            """.trimIndent()

        val out = composed(raw)
        val inbounds = out["inbounds"] as JsonArray

        (inbounds[0] as JsonObject)["tag"]!!.jsonPrimitive.content shouldBe "client-socks"
        (inbounds[1] as JsonObject)["tag"]!!.jsonPrimitive.content shouldBe "client-http"
    }

    // Research §5b.5: substituting our destOverride would start sniffing QUIC and
    // silently change which of the config's own rules match.
    @Test
    fun `the config's own sniffing settings are carried onto our inbound`() {
        val socks = (composed()["inbounds"] as JsonArray)[0] as JsonObject
        val overrides = ((socks["sniffing"] as JsonObject)["destOverride"] as JsonArray)
            .map { it.jsonPrimitive.content }

        overrides shouldBe listOf("http", "tls")
    }

    @Test
    fun `our defaults are used only when the config has no sniffing block`() {
        val noSniffing =
            """
            {
              "inbounds": [ { "tag": "socks", "port": 10808, "protocol": "socks" } ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
            }
            """.trimIndent()
        val socks = (composed(noSniffing)["inbounds"] as JsonArray)[0] as JsonObject
        val overrides = ((socks["sniffing"] as JsonObject)["destOverride"] as JsonArray)
            .map { it.jsonPrimitive.content }

        overrides shouldBe listOf("http", "tls", "quic")
    }

    // Final review I1: an earlier review fixed this exact contract violation in the override
    // branch (`ParsedOverride`'s try/catch); this call site was missed. `compose` must never
    // throw on untrusted config bytes, so a non-string destOverride element (JsonObject/JsonArray)
    // must be skipped, not crash `.jsonPrimitive.content` with IllegalArgumentException.
    @Test
    fun `a malformed destOverride entry is dropped rather than crashing compose`() {
        val malformed =
            """
            {
              "inbounds": [
                {
                  "tag": "socks",
                  "port": 10808,
                  "protocol": "socks",
                  "sniffing": { "enabled": true, "destOverride": ["http", {"not": "a string"}, "tls"] }
                }
              ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
            }
            """.trimIndent()

        val socks = (composed(malformed)["inbounds"] as JsonArray)[0] as JsonObject
        val overrides = ((socks["sniffing"] as JsonObject)["destOverride"] as JsonArray)
            .map { it.jsonPrimitive.content }

        overrides shouldBe listOf("http", "tls")
    }

    // Correction pass: `JsonNull` is itself a `JsonPrimitive`, so the fix above's
    // `(it as? JsonPrimitive)?.content` let a JSON `null` element through as the literal string
    // "null" instead of dropping it — the one case `PassthroughAnalysis.stringOrNull()` (the parity
    // this code claims) explicitly excludes.
    @Test
    fun `a JSON null destOverride entry is dropped, not admitted as the string null`() {
        val withNull =
            """
            {
              "inbounds": [
                {
                  "tag": "socks",
                  "port": 10808,
                  "protocol": "socks",
                  "sniffing": { "enabled": true, "destOverride": ["http", null, "tls"] }
                }
              ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
            }
            """.trimIndent()

        val socks = (composed(withNull)["inbounds"] as JsonArray)[0] as JsonObject
        val overrides = ((socks["sniffing"] as JsonObject)["destOverride"] as JsonArray)
            .map { it.jsonPrimitive.content }

        overrides shouldBe listOf("http", "tls")
    }

    // §5.6: the device-found logcat leak — one line per destination the user reaches.
    @Test
    fun `log is forced to a redacting shape`() {
        val log = composed()["log"] as JsonObject

        log["access"]!!.jsonPrimitive.content shouldBe "none"
        log["loglevel"]!!.jsonPrimitive.content shouldBe "warning"
    }

    @Test
    fun `env points at the resolved asset directory`() {
        val env = composed()["env"] as JsonObject

        env["xray.location.asset"]!!.jsonPrimitive.content shouldBe "/data/geo"
    }

    @Test
    fun `stats policy and metrics are stripped`() {
        val out = composed()

        out["stats"] shouldBe null
        out["metrics"] shouldBe null
        out["policy"] shouldBe null
    }

    @Test
    fun `output is deterministic`() {
        val first = RawConfigComposer.compose(panelLike, settings, "/data/geo", null)
        val second = RawConfigComposer.compose(panelLike, settings, "/data/geo", null)

        (first as ComposeResult.Ok).json shouldBe (second as ComposeResult.Ok).json
    }

    @Test
    fun `malformed input is reported, never thrown`() {
        RawConfigComposer.compose("not json", settings, "/data/geo", null) shouldBe
            ComposeResult.Failed(ComposeFailure.NotJson)
        RawConfigComposer.compose("""{"inbounds":[]}""", settings, "/data/geo", null) shouldBe
            ComposeResult.Failed(ComposeFailure.NoOutbounds)
    }

    @Test
    fun `the composed config never contains the fixed panel ports`() {
        val json = (RawConfigComposer.compose(panelLike, settings, "/data/geo", null) as ComposeResult.Ok).json

        json shouldNotContain "10808"
        json shouldNotContain "10809"
        json shouldContain "41080"
    }

    // A Remnawave XRAY_JSON subscription delivers a JSON array at the root, not
    // an object — the most likely real-world malformed-for-us input.
    @Test
    fun `a JSON array root is reported as not JSON`() {
        RawConfigComposer.compose(
            """[{"outbounds":[{"protocol":"vless"}]}]""",
            settings,
            "/data/geo",
            null,
        ) shouldBe ComposeResult.Failed(ComposeFailure.NotJson)
    }

    // Controller ruling: running the config as written means honouring an
    // explicit sniffing:false too. Re-enabling it because our settings ask for
    // sniffing would be the app second-guessing an author's own choice, even
    // though it means the config's own domain/geosite rules stop matching
    // under tun2socks.
    @Test
    fun `sniffing explicitly disabled by the config is not silently re-enabled`() {
        val disabled =
            """
            {
              "inbounds": [
                { "tag": "socks", "port": 10808, "protocol": "socks", "sniffing": { "enabled": false } }
              ],
              "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
            }
            """.trimIndent()
        val socks = (composed(disabled)["inbounds"] as JsonArray)[0] as JsonObject

        socks["sniffing"] shouldBe null
    }

    private val override =
        OverrideBlocks(
            routingJson =
            """{ "domainStrategy": "IPIfNonMatch", """ +
                """"rules": [ { "type": "field", "domain": ["geosite:cn"], "outboundTag": "direct" } ] }""",
            dnsJson = """{ "servers": ["1.1.1.1"] }""",
            extraOutboundsJson = emptyList(),
        )

    @Test
    fun `the override branch replaces the config's routing and dns wholesale`() {
        val result = RawConfigComposer.compose(panelLike, settings, "/data/geo", override)
        val out = Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject

        val routing = out["routing"] as JsonObject
        routing["balancers"] shouldBe null
        routing["domainMatcher"] shouldBe null
        (routing["domainStrategy"]!!.jsonPrimitive.content) shouldBe "IPIfNonMatch"
        ((out["dns"] as JsonObject)["servers"] as JsonArray).size shouldBe 1
    }

    @Test
    fun `the override branch leaves the config's outbounds in place`() {
        val result = RawConfigComposer.compose(panelLike, settings, "/data/geo", override)
        val out = Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject

        (out["outbounds"] as JsonArray).size shouldBe 3
    }

    @Test
    fun `the override branch appends outbounds the config lacks`() {
        val proxyOnly =
            """{ "outbounds": [ { "tag": "proxy", "protocol": "vless" } ] }"""
        val withExtras =
            override.copy(
                extraOutboundsJson =
                listOf(
                    """{ "tag": "direct", "protocol": "freedom" }""",
                    """{ "tag": "block", "protocol": "blackhole" }""",
                ),
            )
        val result = RawConfigComposer.compose(proxyOnly, settings, "/data/geo", withExtras)
        val out = Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject
        val tags = (out["outbounds"] as JsonArray).map { (it as JsonObject)["tag"]!!.jsonPrimitive.content }

        tags shouldBe listOf("proxy", "direct", "block")
    }

    // Spec §4.2: our own rules are what run, so our sniffing defaults are correct here.
    @Test
    fun `the override branch uses our sniffing defaults, not the config's`() {
        val result = RawConfigComposer.compose(panelLike, settings, "/data/geo", override)
        val out = Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject
        val socks = (out["inbounds"] as JsonArray)[0] as JsonObject
        val overrides = ((socks["sniffing"] as JsonObject)["destOverride"] as JsonArray)
            .map { it.jsonPrimitive.content }

        overrides shouldBe listOf("http", "tls", "quic")
    }

    private val dnsPlanWithFakeDns =
        DnsPlan(
            servers = listOf(DnsServerSpec(address = "1.1.1.1")),
            hosts = emptyMap(),
            fakeDns = true,
            directMatch = null,
            proxyMatch = null,
        )

    // Important 3 (review round 1): overrideBlocks() itself had no test calling
    // it and feeding the result through compose — every other override-branch
    // test hand-constructs OverrideBlocks from literal JSON. This closes that
    // gap by wiring the two functions together the way Task 9 will.
    @Test
    fun `overrideBlocks feeds straight into compose as a valid override`() {
        val blocks = XrayConfigGenerator.overrideBlocks(settings)
        val result = RawConfigComposer.compose(panelLike, settings, "/data/geo", blocks)

        result.shouldBeOk()
        val out = Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject
        val tags = (out["outbounds"] as JsonArray).map { (it as JsonObject)["tag"]!!.jsonPrimitive.content }
        tags.contains("dns-out") shouldBe false
    }

    // Important 1 (review round 1): the override branch was silently dropping
    // fakeDns from sniffing while overrideBlocks()'s own dnsJson still asked
    // for it — a passthrough profile with fakeDns on would sniff nothing extra
    // while xray's dns block expected synthetic IPs. Covers both the dns-out
    // outbound and the sniffing fix through the same real wiring.
    @Test
    fun `overrideBlocks with a fakeDns plan produces dns-out and a fakedns-aware override`() {
        val settingsWithFakeDns = settings.copy(dns = dnsPlanWithFakeDns)
        val blocks = XrayConfigGenerator.overrideBlocks(settingsWithFakeDns)
        val result = RawConfigComposer.compose(panelLike, settingsWithFakeDns, "/data/geo", blocks)

        result.shouldBeOk()
        val out = Json.parseToJsonElement((result as ComposeResult.Ok).json) as JsonObject
        val tags = (out["outbounds"] as JsonArray).map { (it as JsonObject)["tag"]!!.jsonPrimitive.content }
        tags.contains("dns-out") shouldBe true

        val socks = (out["inbounds"] as JsonArray)[0] as JsonObject
        val overrides = ((socks["sniffing"] as JsonObject)["destOverride"] as JsonArray)
            .map { it.jsonPrimitive.content }
        overrides.contains("fakedns") shouldBe true
    }
}
