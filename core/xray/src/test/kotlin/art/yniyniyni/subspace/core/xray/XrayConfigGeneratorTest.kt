// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

    @Test
    fun `refuses a non-vless outbound rather than emitting a broken config`() {
        val stream = StreamSettings(network = "tcp", security = Security.None)
        val trojan = TrojanOutbound("host.example", 443, "pw", stream)
        val profile = Profile(id = "id", name = "n", outbound = trojan)

        val result = XrayConfigGenerator.generate(profile, settings)

        result.shouldBeInstanceOf<ConfigResult.Unsupported>()
    }
}
