// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

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

    /**
     * Finding 1 (round 1 review): [directMatch][DnsPlan.directMatch] and
     * [proxyMatch][DnsPlan.proxyMatch] are both null whenever the plan came from
     * an app-level setting with no profile resolver, or from a profile naming
     * only hosts. Without an unconditional catch-all ahead of the hijack rule,
     * the built-in resolver's own port-53 query would match the hijack and loop
     * back into the resolver that issued it. This asserts the catch-all is
     * present, and ahead of the hijack, even with no specific match set.
     */
    @Test
    fun `a resolver catch-all rule precedes the hijack rule when no match is set`() {
        val json = generateWith(dns = plan.copy(directMatch = null, proxyMatch = null))

        json shouldContain """{ "type": "field", "inboundTag": ["dns-module"], "outboundTag": "proxy" }"""
        val catchAll = json.indexOf(""""inboundTag": ["dns-module"], "outboundTag": "proxy" }""")
        val hijack = json.indexOf(""""outboundTag": "dns-out"""")
        (catchAll in 0 until hijack) shouldBe true
    }

    /**
     * Finding 2 (round 1 review): `json shouldContain "\"fakedns\""` alone is
     * already satisfied by the `destOverride` line the next test checks, so it
     * cannot fail if the `dns.servers` entry — the half that actually resolves
     * FakeDNS domains — went missing. This pins the `servers` array's own text.
     */
    @Test
    fun `fakedns adds the server entry`() {
        val json = generateWith(dns = plan.copy(fakeDns = true))

        json shouldContain
            """
            |    "servers": [
            |      "fakedns",
            """.trimMargin()
    }

    @Test
    fun `fakedns adds the sniffing override`() {
        val json = generateWith(dns = plan.copy(fakeDns = true))

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

    /**
     * Finding 3 (round 1 review): the fixture's single-entry `hosts` map gives
     * `toSortedMap()` nothing to sort, and calling `generateWith(plan)` twice
     * reuses the same map instance either way — so the old determinism test
     * could not detect a `hosts`-ordering bug. This builds two maps with the
     * same two entries in opposite insertion order and requires identical output.
     */
    @Test
    fun `generation is deterministic regardless of hosts map insertion order`() {
        val insertedAB = linkedMapOf("a.example" to "10.0.0.1", "b.example" to "10.0.0.2")
        val insertedBA = linkedMapOf("b.example" to "10.0.0.2", "a.example" to "10.0.0.1")

        generateWith(dns = plan.copy(hosts = insertedAB)) shouldBe generateWith(dns = plan.copy(hosts = insertedBA))
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
