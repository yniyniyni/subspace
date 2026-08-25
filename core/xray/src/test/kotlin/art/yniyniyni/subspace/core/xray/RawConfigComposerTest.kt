// SPDX-License-Identifier: AGPL-3.0-or-later
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
        socks["tag"]!!.jsonPrimitive.content shouldBe "socks-in"
        (inbounds[1] as JsonObject)["port"]!!.jsonPrimitive.content shouldBe "41081"
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
}
