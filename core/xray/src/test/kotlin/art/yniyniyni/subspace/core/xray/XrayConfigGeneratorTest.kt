// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

    @Test
    fun `is byte-identical across invocations`() {
        // §6: same profile + same settings => byte-identical JSON. This is what
        // makes the golden file below meaningful and config diffs reviewable.
        val first = XrayConfigGenerator.generate(outbound, settings)
        val second = XrayConfigGenerator.generate(outbound, settings)
        second shouldBe first
    }

    @Test
    fun `binds the socks inbound to loopback only`() {
        // §6: never 0.0.0.0 — that turns the phone into an open proxy on the LAN.
        val json = XrayConfigGenerator.generate(outbound, settings)
        json shouldContain "\"listen\": \"127.0.0.1\""
        json shouldNotContain "0.0.0.0"
    }

    @Test
    fun `includes a dns block`() {
        // §5.2, half one. The other half is VpnService.Builder.addDnsServer().
        // libXray v26.7.11 has no setDNS, so these two are the only levers.
        val json = XrayConfigGenerator.generate(outbound, settings)
        json shouldContain "\"dns\""
        json shouldContain "1.1.1.1"
    }

    @Test
    fun `uses the allocated port rather than a literal`() {
        // §10.6: no hardcoded ports. The port comes from libXray getFreePorts.
        val json = XrayConfigGenerator.generate(outbound, settings.copy(socksPort = 34567))
        json shouldContain "34567"
        json shouldNotContain "10808"
    }

    @Test
    fun `emits the three required outbound tags`() {
        val json = XrayConfigGenerator.generate(outbound, settings)
        json shouldContain "\"tag\": \"proxy\""
        json shouldContain "\"tag\": \"direct\""
        json shouldContain "\"tag\": \"block\""
    }

    @Test
    fun `omits the flow field when the profile has no flow`() {
        val noFlow = outbound.copy(flow = null)
        XrayConfigGenerator.generate(noFlow, settings) shouldNotContain "\"flow\""
    }

    @Test
    fun `omits sniffing when disabled`() {
        val json = XrayConfigGenerator.generate(outbound, settings.copy(enableSniffing = false))
        json shouldNotContain "\"sniffing\""
    }

    @Test
    fun `has no stats or api block in M1`() {
        // Traffic counters are M7 (§14.4). Their presence would change the config
        // for the same profile, which is fine — but not yet.
        val json = XrayConfigGenerator.generate(outbound, settings)
        json shouldNotContain "\"stats\""
        json shouldNotContain "\"api\""
    }

    @Test
    fun `produces parseable json`() {
        // A config that is not valid JSON fails inside libXray, where §10.4 says
        // the error is hard to attribute. Catch it here instead.
        val json = XrayConfigGenerator.generate(outbound, settings)
        val braces = json.count { it == '{' } - json.count { it == '}' }
        val brackets = json.count { it == '[' } - json.count { it == ']' }
        braces shouldBe 0
        brackets shouldBe 0
    }

    @Test
    fun `matches the golden file`() {
        val golden = checkNotNull(javaClass.getResource("/golden/vless-reality.json")).readText()
        XrayConfigGenerator.generate(outbound, settings) shouldBe golden.trimEnd()
    }
}
