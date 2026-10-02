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
            // A 24-char run from BASE64_BLOB_PATTERN's class needs `_`, `+` and `-` too.
            "abc_def+ghi-jkl/mnopqrst",
            // The same run one character short of the 24-char minimum: a negative control.
            "abc_def+ghi-jkl/mnopqrs",
            // Mixed-case positional keywords, exercising KEYED_HOST_PATTERN and
            // LABELLED_HOST_PATTERN's case-insensitivity on pure ASCII input.
            "Address=corp",
            "LOOKUP corp",
            "SNI=corp",
            "Domain: corp",
            "Dial corp",
            // IPv6-dense corpus (M8.5 spec §3.2, Task 21): exercises
            // redactIpv6Candidates's maximal-run scan and its dot-boundary
            // check directly, rather than leaving them to chance in the
            // generated corpus below.
            "connect ::1 refused",
            // IPv4-mapped notation: IPV4_PATTERN claims "1.2.3.4" first (it runs
            // before the IPv6 pass), so by the time the scan sees this the "."
            // immediately after "::ffff:" is still there but the digits after it
            // are gone — a dot-boundary case that doesn't depend on IPV4_PATTERN's
            // order to be meaningful.
            "mapped ::ffff:1.2.3.4 blocked",
            "route fe80::abcd%eth0 down",
            // A hex/colon run immediately followed by '.': the dot-boundary check
            // must leave this alone exactly as IPV6_PATTERN's own lookahead does,
            // even though "12:34:ab" alone (sans the ".5") would be a candidate.
            // Picked so nothing downstream (HOSTNAME_PATTERN needs 2+ letters after
            // the last dot) redacts it either — confirmed against HEAD's redact()
            // this is a true no-op, not masked by a later pass.
            "build 12:34:ab.5 end",
            // Single colons only: never reaches the >= 2 colon gate at all.
            "ports host1:443 host2:8080 done",
            // Hex letters with no "::" and only two colons: isIpv6Address's
            // hex-letter branch, not its "::" branch.
            "weird a:b:c end",
            // Decimal-only with three colons: isIpv6Address's third branch
            // (MIN_DECIMAL_ONLY_IPV6_COLONS), which none of the fixed cases above
            // exercise — "started at 12:34:56" (RedactionTest) has only two.
            "clock 01:02:03:04 marks",
            // Malformed back-to-back "::": not a well-formed address, but the
            // pattern doesn't validate RFC shape, only candidate shape plus
            // isIpv6Address — both engines must agree it's still redacted.
            "malformed ::1::2 input",
            "upstream cdn.example.org:5353 failed",
        )

    private val pieces =
        listOf(
            "203.0.113.7",
            "10.7.0.1",
            "2001:db8::42",
            "fe80::1%wlan0",
            "example.com",
            "a.b.c.example.org",
            "11111111-2222-3333-4444-555555555555",
            "https://x.example/p?q=1",
            "ws://h:80/",
            "tcp:",
            "udp",
            "address",
            "Server",
            "HOST",
            "sni",
            "domain",
            "dial",
            "lookup",
            ":",
            "=",
            "\"",
            ",",
            " ",
            "\t",
            "Zm9vYmFyYmF6cXV4cXV1eGNvcmdlZ3JhdWx0",
            "aGVsbG8=",
            "443",
            "[",
            "]",
            "->",
            "<redacted>",
            "accepted",
            "proxy",
            "direct",
            "[Info]",
            "[Warning]",
            "app/proxyman",
            "i/o timeout",
            // IPv6-dense pieces (Task 21), to let the generated corpus produce
            // more of the shapes the fixed cases above pin directly.
            "::",
            "ffff:",
            "%eth0",
            "01:02:03:04",
            "ab:cd",
            ".",
            ":443",
            "a:b:c",
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
