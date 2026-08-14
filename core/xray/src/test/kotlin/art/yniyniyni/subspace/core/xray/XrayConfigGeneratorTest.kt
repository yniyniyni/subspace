// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class XrayConfigGeneratorTest {
    private val reality =
        Security.Reality(
            serverName = "www.microsoft.com",
            publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            shortId = "0123abcd",
            fingerprint = "chrome",
            spiderX = "/",
        )

    private val outbound =
        VlessOutbound(
            address = "example.com",
            port = 443,
            uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
            flow = "xtls-rprx-vision",
            stream = StreamSettings(network = "tcp", security = reality),
        )

    private val settings =
        TunnelSettings(
            socksPort = 10808,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
        )

    private val profile = Profile(id = "id", name = "n", outbound = outbound)

    /**
     * `generate` now returns a [ConfigResult], not a bare String. Every test
     * below exercises the VLESS path, so this unwraps [ConfigResult.Ok] once
     * rather than repeating the cast — Step 1's new test covers the
     * [ConfigResult.Unsupported] path directly.
     */
    private fun generateJson(
        outbound: VlessOutbound,
        settings: TunnelSettings,
    ): String {
        val profile = Profile(id = "id", name = "n", outbound = outbound)
        val result = XrayConfigGenerator.generate(profile, settings)
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok, got $result" }
        return result.json
    }

    @Test
    fun `is byte-identical across invocations`() {
        // §6: same profile + same settings => byte-identical JSON. This is what
        // makes the golden file below meaningful and config diffs reviewable.
        val first = generateJson(outbound, settings)
        val second = generateJson(outbound, settings)
        second shouldBe first
    }

    @Test
    fun `binds the socks inbound to loopback only`() {
        // §6: never 0.0.0.0 — that turns the phone into an open proxy on the LAN.
        val json = generateJson(outbound, settings)
        json shouldContain "\"listen\": \"127.0.0.1\""
        json shouldNotContain "0.0.0.0"
    }

    @Test
    fun `includes a dns block`() {
        // §5.2, half one. The other half is VpnService.Builder.addDnsServer().
        // libXray v26.7.11 has no setDNS, so these two are the only levers.
        val json = generateJson(outbound, settings)
        json shouldContain "\"dns\""
        json shouldContain "1.1.1.1"
    }

    @Test
    fun `uses the allocated port rather than a literal`() {
        // §10.6: no hardcoded ports. The port comes from libXray getFreePorts.
        val json = generateJson(outbound, settings.copy(socksPort = 34567))
        json shouldContain "34567"
        json shouldNotContain "10808"
    }

    @Test
    fun `emits the three required outbound tags`() {
        val json = generateJson(outbound, settings)
        json shouldContain "\"tag\": \"proxy\""
        json shouldContain "\"tag\": \"direct\""
        json shouldContain "\"tag\": \"block\""
    }

    @Test
    fun `omits the flow field when the profile has no flow`() {
        val noFlow = outbound.copy(flow = null)
        generateJson(noFlow, settings) shouldNotContain "\"flow\""
    }

    @Test
    fun `omits sniffing when disabled`() {
        val json = generateJson(outbound, settings.copy(enableSniffing = false))
        json shouldNotContain "\"sniffing\""
    }

    @Test
    fun `has no stats or api block in M1`() {
        // Traffic counters are M7 (§14.4). Their presence would change the config
        // for the same profile, which is fine — but not yet.
        val json = generateJson(outbound, settings)
        json shouldNotContain "\"stats\""
        json shouldNotContain "\"api\""
    }

    @Test
    fun `produces parseable json`() {
        // A config that is not valid JSON fails inside libXray, where §10.4 says
        // the error is hard to attribute. Catch it here instead.
        val json = generateJson(outbound, settings)
        val braces = json.count { it == '{' } - json.count { it == '}' }
        val brackets = json.count { it == '[' } - json.count { it == ']' }
        braces shouldBe 0
        brackets shouldBe 0
    }

    @Test
    fun `matches the golden file`() {
        val golden = checkNotNull(javaClass.getResource("/golden/vless-reality.json")).readText()
        generateJson(outbound, settings) shouldBe golden.trimEnd()
    }

    // The M1 tunnel is proven on hardware. Routing must not drift it, so this
    // pins the exact block a null rule set produces — byte for byte.
    @Test
    fun `no rule set produces the M1 routing block unchanged`() {
        val json = (XrayConfigGenerator.generate(profile, settings) as ConfigResult.Ok).json

        json shouldContain
            """
            |  "routing": {
            |    "domainStrategy": "IPIfNonMatch",
            |    "rules": []
            |  }
            """.trimMargin()
    }

    @Test
    @Suppress("Indentation") // ktlint requires this nested constructor indentation; detekt disagrees.
    fun `a rule set is emitted into the routing block`() {
        val routed =
            settings.copy(
                routing =
                    RoutingRuleSet(
                        name = "test",
                        buckets =
                            mapOf(
                                RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
                                RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8")),
                            ),
                        domainStrategy = DomainStrategy.AS_IS,
                    ),
            )

        val json = (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json

        json shouldContain
            """
            |  "routing": {
            |    "domainStrategy": "AsIs",
            |    "rules": [
            |      { "type": "field", "domain": ["geosite:category-ads-all"], "outboundTag": "block" },
            |      { "type": "field", "ip": ["10.0.0.0/8"], "outboundTag": "direct" }
            |    ]
            |  }
            """.trimMargin()
    }

    @Test
    fun `routing output escapes entries when callers bypass entry validation`() {
        val unvalidatedEntry = "safe\"\n\u0001\"outboundTag\": \"block"
        val routing =
            RoutingRuleSet(
                name = "test",
                buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf(unvalidatedEntry))),
            )
        val routed = settings.copy(routing = routing)

        val json = (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json

        json shouldContain
            """{ "type": "field", "domain": ["safe\"\n\u0001\"outboundTag\": \"block"], "outboundTag": "proxy" }"""
    }

    @Test
    fun `IP on demand strategy uses the Xray wire value`() {
        val routed =
            settings.copy(
                routing = RoutingRuleSet(name = "test", domainStrategy = DomainStrategy.IP_ON_DEMAND),
            )

        val json = (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json

        json shouldContain """"domainStrategy": "IPOnDemand"""
    }

    @Test
    @Suppress("Indentation") // ktlint requires this nested constructor indentation; detekt disagrees.
    fun `generation stays deterministic with a rule set`() {
        val routed =
            settings.copy(
                routing =
                    RoutingRuleSet(
                        name = "test",
                        buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("a.example", "b.example"))),
                    ),
            )

        val first = (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json
        val second = (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json

        first shouldBe second
    }

    /**
     * Asserting the payload, not just the type.
     *
     * `ConfigResult.Unsupported.protocol` is rendered straight to the user by
     * `HomeScreen`. The guarantee that it is a fixed literal rather than, say,
     * `outbound.address` — which would be a §5.6 leak into the UI — rested on
     * source inspection alone while this asserted only the type. It is also the
     * one place a copy-paste between the four branches would go unnoticed:
     * every wrong answer is still an `Unsupported`.
     */
    // ── Transport emission ──────────────────────────────────────────────────
    //
    // Every key below is verified against Xray-core v26.7.11 — the version §14.3
    // pins — in `infra/conf/transport_method.go`: `WebSocketConfig` (`path`,
    // `host`, `headers`), `GRPCConfig` (`serviceName`), `SplitHTTPConfig`
    // (`path`, `host`, `mode`). §10.5: an invented key here is either silently
    // ignored or rejects the whole config, and neither is visible from Kotlin.
    //
    // This whole block exists because `appendStreamSettings` used to emit
    // `"network"` and the security block and nothing else, so a ws/grpc/xhttp
    // profile dialled with none of its own transport options. That is what made
    // `StoredProfile.connectable` demote those rows to "not supported by this
    // build yet" rather than fix the generator.

    private fun streamSettingsBlockOf(json: String): String =
        json.substringAfter("\"streamSettings\": {").substringBefore("\n      }")

    /** The shared VLESS outbound with one transport swapped in — the only axis these vary on. */
    private fun vlessWith(
        network: String,
        security: Security,
        transport: TransportOptions,
    ): VlessOutbound =
        outbound.copy(stream = StreamSettings(network = network, security = security, transport = transport))

    @Test
    fun `emits wsSettings with the stored path and headers`() {
        val ws =
            vlessWith("ws", Security.None, TransportOptions.WebSocket("/chat", mapOf("Host" to "cdn.example")))

        val json = generateJson(ws, settings)

        json shouldContain "\"network\": \"ws\""
        json shouldContain "\"wsSettings\": {"
        json shouldContain "\"path\": \"/chat\""
        json shouldContain "\"Host\": \"cdn.example\""
    }

    @Test
    fun `omits the ws headers block when the source carried none`() {
        val ws =
            vlessWith("ws", Security.None, TransportOptions.WebSocket("/", emptyMap()))

        val json = generateJson(ws, settings)

        json shouldContain "\"wsSettings\": {"
        // An empty `headers` object is legal but says something the source did
        // not: it asserts "no headers" where the link merely never mentioned any.
        streamSettingsBlockOf(json) shouldNotContain "\"headers\""
    }

    @Test
    fun `emits grpcSettings with the stored service name`() {
        val grpc =
            vlessWith("grpc", Security.None, TransportOptions.Grpc("GunService"))

        val json = generateJson(grpc, settings)

        json shouldContain "\"network\": \"grpc\""
        json shouldContain "\"grpcSettings\": {"
        json shouldContain "\"serviceName\": \"GunService\""
    }

    @Test
    fun `emits xhttpSettings with the stored path, host and mode`() {
        val xhttp =
            vlessWith("xhttp", reality, TransportOptions.Xhttp("/down", "cdn.example", "stream-up"))

        val json = generateJson(xhttp, settings)

        json shouldContain "\"network\": \"xhttp\""
        json shouldContain "\"xhttpSettings\": {"
        json shouldContain "\"path\": \"/down\""
        json shouldContain "\"host\": \"cdn.example\""
        json shouldContain "\"mode\": \"stream-up\""
    }

    @Test
    fun `omits xhttp host and mode when the source left them unset`() {
        val xhttp =
            vlessWith("xhttp", Security.None, TransportOptions.Xhttp("/", host = null, mode = null))

        val json = generateJson(xhttp, settings)

        json shouldContain "\"xhttpSettings\": {"
        json shouldContain "\"path\": \"/\""
        // Xray falls back to the dial address for an absent Host and to `auto`
        // for an absent mode. Emitting `""` instead would be a different
        // request on the wire than the link described.
        streamSettingsBlockOf(json) shouldNotContain "\"host\""
        streamSettingsBlockOf(json) shouldNotContain "\"mode\""
    }

    @Test
    fun `emits no transport block for a plain tcp profile`() {
        // The tcp path must not gain a `rawSettings` block it never had — the
        // golden file pins that, and this says why.
        streamSettingsBlockOf(generateJson(outbound, settings)) shouldNotContain "Settings\": {\n          \"path\""
    }

    @Test
    fun `emits no transport block when a ws profile carries no options`() {
        // TransportOptions.None on a non-tcp network: the source named the
        // transport but nothing about it. Xray supplies that transport's own
        // defaults when its settings object is absent (verified in
        // v26.7.11 `StreamConfig.Build` — a nil `WSSettings` skips the block
        // rather than erroring), which is exactly what "unspecified" means.
        val ws = vlessWith("ws", Security.None, TransportOptions.None)

        val json = generateJson(ws, settings)

        json shouldContain "\"network\": \"ws\""
        streamSettingsBlockOf(json) shouldNotContain "wsSettings"
    }

    @Test
    fun `still produces parseable json for every transport`() {
        val transports =
            listOf(
                StreamSettings("ws", Security.None, TransportOptions.WebSocket("/p", mapOf("Host" to "h.example"))),
                StreamSettings("grpc", Security.None, TransportOptions.Grpc("svc")),
                StreamSettings("xhttp", reality, TransportOptions.Xhttp("/p", "h.example", "auto")),
                StreamSettings("xhttp", Security.None, TransportOptions.Xhttp("/", null, null)),
            )

        transports.forEach { stream ->
            withClue(stream.network) {
                val json = generateJson(outbound.copy(stream = stream), settings)
                (json.count { it == '{' } - json.count { it == '}' }) shouldBe 0
                (json.count { it == '[' } - json.count { it == ']' }) shouldBe 0
            }
        }
    }

    // ── Loopback HTTP inbound ────────────────────────────────────────────────

    @Test
    fun `no http port emits only the socks inbound`() {
        val json = (XrayConfigGenerator.generate(profile, settings) as ConfigResult.Ok).json

        json shouldNotContain """"protocol": "http""""
    }

    @Test
    fun `an http port emits a second loopback inbound`() {
        val json =
            (XrayConfigGenerator.generate(profile, settings.copy(httpPort = 10809)) as ConfigResult.Ok).json

        json shouldContain """      "tag": "http-in","""
        json shouldContain """      "protocol": "http","""
        json shouldContain """      "port": 10809,"""
    }

    // §6: never 0.0.0.0. An open HTTP proxy on the LAN is trivially usable from
    // any browser on the network.
    @Test
    fun `the http inbound binds to loopback only`() {
        val json =
            (XrayConfigGenerator.generate(profile, settings.copy(httpPort = 10809)) as ConfigResult.Ok).json

        json shouldNotContain "0.0.0.0"
        json.split(""""listen": """).drop(1).forEach { it shouldStartWith "\"127.0.0.1\"" }
    }

    @Test
    fun `the config with an http inbound is still deterministic`() {
        val routed = settings.copy(httpPort = 10809)

        (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json shouldBe
            (XrayConfigGenerator.generate(profile, routed) as ConfigResult.Ok).json
    }

    @Test
    fun `refuses a non-vless outbound rather than emitting a broken config`() {
        val stream = StreamSettings(network = "tcp", security = Security.None)
        val trojan = TrojanOutbound("host.example", 443, "pw", stream)
        val profile = Profile(id = "id", name = "n", outbound = trojan)

        val result = XrayConfigGenerator.generate(profile, settings)

        result.shouldBeInstanceOf<ConfigResult.Unsupported>()
        result.protocol shouldBe "Trojan"
    }

    @Test
    fun `names every unsupported protocol exactly, and never the address`() {
        val stream = StreamSettings(network = "tcp", security = Security.None)
        val secretAddress = "host.example"
        val unsupported =
            listOf<Pair<Outbound, String>>(
                VmessOutbound(secretAddress, 443, "u", 0, "auto", stream) to "VMess",
                TrojanOutbound(secretAddress, 443, "pw", stream) to "Trojan",
                ShadowsocksOutbound(secretAddress, 8388, "aes-256-gcm", "pw") to "Shadowsocks",
                SocksOutbound(secretAddress, 1080, "user", "pw") to "SOCKS",
            )

        unsupported.forEach { (outbound, expected) ->
            withClue(expected) {
                val profile = Profile(id = "id", name = "n", outbound = outbound)
                val result = XrayConfigGenerator.generate(profile, settings)

                result.shouldBeInstanceOf<ConfigResult.Unsupported>()
                result.protocol shouldBe expected
                // §5.6: this string reaches the UI. It must be a fixed literal.
                result.protocol shouldNotContain secretAddress
            }
        }
    }
}
