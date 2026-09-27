// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

/**
 * M8.5 spec §3.2 (amended): the gated redact() must be indistinguishable from
 * the ungated one. Any divergence is a gate that skipped a pattern which could
 * have matched, which is a leak.
 */
class RedactionOracleTest {
    private val fixed =
        listOf(
            "failed to dial tcp 203.0.113.44:443: connection refused",
            "[Warning] app/dns: failed to lookup example.com via https://dns.example.net/dns-query",
            "from tcp:127.0.0.1:43210 accepted tcp:www.google.com:443 [socks -> proxy]",
            "vless://11111111-2222-3333-4444-555555555555@host.example:443?security=reality#name",
            "address: \"srv.example.org\", sni=cdn.example.com",
            "dial udp [2001:db8::1]:53: i/o timeout",
            "vpnserver: connection refused",
            "pbk=Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0",
            "teardown[tun2socks-stop] enter",
            "geoip.dat, geosite.dat",
            "plain text with no secrets at all",
            "",
        )

    private val pieces =
        listOf(
            "203.0.113.7", "10.7.0.1", "2001:db8::42", "fe80::1%wlan0", "example.com", "a.b.c.example.org",
            "11111111-2222-3333-4444-555555555555", "https://x.example/p?q=1", "ws://h:80/", "tcp:", "udp",
            "address", "Server", "HOST", "sni", "domain", "dial", "lookup", ":", "=", "\"", ",", " ", "\t",
            "Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0", "aGVsbG8=", "443", "[", "]", "->", "<redacted>",
            "accepted", "proxy", "direct", "[Info]", "[Warning]", "app/proxyman", "i/o timeout",
        )

    @Test
    fun `the gated redaction equals the oracle on fixed samples`() {
        fixed.forEach { line -> withClue(line) { redact(line) shouldBe oracleRedact(line) } }
    }

    @Test
    fun `the gated redaction equals the oracle on a generated corpus`() {
        val rnd = Random(20260926)
        repeat(50_000) {
            val line = buildString { repeat(rnd.nextInt(1, 14)) { append(pieces[rnd.nextInt(pieces.size)]) } }
            withClue(line) { redact(line) shouldBe oracleRedact(line) }
        }
    }
}
