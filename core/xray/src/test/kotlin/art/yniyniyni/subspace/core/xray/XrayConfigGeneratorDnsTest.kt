// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class XrayConfigGeneratorDnsTest {
    @Suppress("Indentation") // ktlint requires this nested constructor indentation; detekt disagrees.
    private val plan =
        DnsPlan(
            servers =
                listOf(
                    DnsServerSpec("8.8.8.8", domains = listOf("geosite:cn"), skipFallback = true),
                    DnsServerSpec("https://cloudflare-dns.com/dns-query"),
                ),
            hosts = mapOf("cloudflare-dns.com" to "1.1.1.1"),
            fakeDns = false,
            directMatch = "8.8.8.8",
            proxyMatch = "cloudflare-dns.com",
        )

    @Test
    fun `a null plan reproduces the M1 dns block exactly`() {
        val json = generateWith(dns = null)

        json shouldContain """"servers": ["1.1.1.1"]"""
        json shouldNotContain "dns-out"
        json shouldNotContain "dns-module"
    }

    @Test
    fun `the domestic server carries its domains and skipFallback`() {
        val json = generateWith(dns = plan)

        json shouldContain """"address": "8.8.8.8""""
        json shouldContain """"domains": ["geosite:cn"]"""
        json shouldContain """"skipFallback": true"""
    }

    @Test
    fun `never emits a local scheme`() {
        val json = generateWith(dns = plan)

        json shouldNotContain "+local"
    }

    @Test
    fun `emits the dns outbound`() {
        generateWith(dns = plan) shouldContain """{ "tag": "dns-out", "protocol": "dns" }"""
    }

    @Test
    fun `resolver rules precede the hijack rule`() {
        val json = generateWith(dns = plan)

        val directRule = json.indexOf(""""ip": ["8.8.8.8"]""")
        val proxyRule = json.indexOf(""""domain": ["cloudflare-dns.com"]""")
        val hijack = json.indexOf(""""outboundTag": "dns-out"""")

        (directRule in 0 until hijack) shouldBe true
        (proxyRule in 0 until hijack) shouldBe true
    }

    @Test
    fun `the hijack matches tcp and udp on port 53`() {
        generateWith(dns = plan) shouldContain
            """{ "type": "field", "network": "tcp,udp", "port": 53, "outboundTag": "dns-out" }"""
    }

    @Test
    fun `fakedns adds the server entry and the sniffing override`() {
        val json = generateWith(dns = plan.copy(fakeDns = true))

        json shouldContain """"fakedns""""
        json shouldContain """"destOverride": ["http", "tls", "quic", "fakedns"]"""
    }

    @Test
    fun `without fakedns the sniffing block is unchanged`() {
        generateWith(dns = plan) shouldContain """"destOverride": ["http", "tls", "quic"]"""
    }

    @Test
    fun `generation stays byte-identical across invocations`() {
        generateWith(dns = plan) shouldBe generateWith(dns = plan)
    }

    @Test
    fun `matches the split golden file`() {
        val expected =
            requireNotNull(javaClass.getResource("/golden/vless-reality-dns-split.json")).readText().trim()

        generateWith(dns = plan).trim() shouldBe expected
    }

    private fun generateWith(dns: DnsPlan?): String {
        val settings =
            TunnelSettings(
                socksPort = 10808,
                dnsServer = "1.1.1.1",
                enableSniffing = true,
                dns = dns,
            )
        val result = XrayConfigGenerator.generate(GOLDEN_PROFILE, settings)
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok, got $result" }
        return result.json
    }
}
