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
            // IPv4-mapped notation: IPV4_PATTERN runs before the IPv6 pass and
            // already claims the whole dotted part ("1.2.3.4" -> SENTINEL), so
            // by the time the candidate scan sees this string the "." is gone
            // entirely — this is *not* a dot-boundary case (nothing borders a
            // literal "." any more by then). It's a plain "::ffff:" run the
            // scan must still catch after an earlier pass has already rewritten
            // part of the line — see the sentinel-adjacency cases below for the
            // deliberate dot-boundary and sentinel-boundary tests.
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
            // Fix round 1 (review, Minor 1): a run preceded by '.' with no
            // space in between — the dot-boundary check must reject this
            // candidate exactly as IPV6_PATTERN's own lookbehind would.
            "trace .2001:db8::1 end",
            // An over-long run: 9 groups / 8 colons exceeds IPV6_PATTERN's own
            // `{2,7}` repetition limit (max 7 colons via repeated groups, since
            // the trailing group carries no colon). Neither the old whole-string
            // scan nor the new per-run `matches()` can complete a match that
            // needs an 8th colon, so both must agree: not a candidate.
            "span 1:2:3:4:5:6:7:8:9 end",
            // 5+ hex characters in one group: IPV6_PATTERN's group caps at
            // `{0,4}` hex digits before its colon, so "12345:" can't be
            // consumed by a single repetition — and the run can't start partway
            // through either, since every interior position has a hex
            // predecessor. Both engines must agree: no match anywhere in the run.
            "retry 12345:a:b later",
            // Exactly 8 bare colons: one more than the pattern's `{2,7}` max can
            // represent even with every hex part empty, so the full run can
            // never be consumed start-to-end.
            "clock :::::::: tick",
            // Exactly 9 bare colons: further past the limit than the 8-colon
            // case above, for a second data point on the same boundary.
            "clock ::::::::: tick",
            // A run touching the literal "<redacted>" text already present in
            // the input: redactEveryPattern's very first step rewrites any
            // pre-existing "<redacted>" to SENTINEL before any pattern runs, so
            // this also exercises "a run touching the sentinel" — just via the
            // zeroth step rather than a later pattern's own output.
            "note <redacted>:db8::1 end",
            // A run touching the sentinel a *later* step's own earlier pass
            // produced in this same redactEveryPattern call: UUID_PATTERN runs
            // before the IPv6 pass and turns the UUID into SENTINEL ("R", not a
            // hex digit), so the candidate scan must treat "R" as a non-member
            // boundary and start the next run fresh right after it.
            "id 11111111-2222-3333-4444-555555555555:db8::1 end",
            // Unicode digit (Arabic-Indic ١, U+0661) immediately beside a run:
            // isIpv6AlphabetChar is explicit ASCII Char ranges with no \d, so
            // this must not be treated as part of the run by either engine —
            // confirms there's no Unicode-digit class gap to match ICU's \d.
            "digit ١::1 mark",
            // Several candidates on one line, a mix of what gets redacted and
            // what doesn't: a real address, a dot-excluded non-candidate, and a
            // too-short hex/colon run, all in the same message.
            "first 2001:db8::1 second 12:34:ab.5 third a:b fourth",
            // A clock-shaped run embedded in a longer line, not just the bare
            // fixed case RedactionTest pins on its own — isIpv6Address's
            // false-positive guard (needs a hex letter, "::", or 3+ colons) must
            // still leave a plain two-colon decimal run alone mid-sentence.
            "log entry started at 12:34:56 for the session continues",
            // Sentence-final IPv6 (the leak fix): redacted now, where the oracle
            // passed them through; `expected` models exactly this difference.
            "connection to 2001:db8::1.",
            "peer fd00::1. retrying",
            "address=2001:db8::1.",
            "lookup a:b:. done",
            "clock 12:34:56. done",
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
            // Sentence-final dots (the IPv6 leak fix): a dot followed by a space.
            ". ",
            "2001:db8::1.",
        )

    @Test
    fun `the gated redaction equals the oracle on fixed samples`() {
        fixed.forEach { line -> withClue(line) { redact(line) shouldBe expected(line) } }
    }

    @Test
    fun `the gated redaction equals the oracle on a generated corpus`() {
        val rnd = Random(20260926)
        repeat(50_000) {
            val line = buildString { repeat(rnd.nextInt(1, 14)) { append(pieces[rnd.nextInt(pieces.size)]) } }
            withClue(line) { redact(line) shouldBe expected(line) }
        }
    }

    /**
     * The model check's own sanity: [expected] really is [oracleRedact] on every
     * line without a sentence-final dot after a colon run, so the only lines it
     * could ever disagree with the oracle on are the ones the leak fix targets.
     */
    @Test
    fun `the model is the oracle wherever the leak fix does not apply`() {
        expected("peer 2001:db8::42 closed") shouldBe oracleRedact("peer 2001:db8::42 closed")
        expected("build 12:34:ab.5 end") shouldBe oracleRedact("build 12:34:ab.5 end")
        expected("connection to 2001:db8::1.") shouldBe "connection to <redacted>."
        oracleRedact("connection to 2001:db8::1.") shouldBe "connection to 2001:db8::1."
    }

    /**
     * What `redact` must return: [oracleRedact], the frozen pre-gate copy, with
     * exactly one deliberate change (the sentence-final IPv6 leak, ARCHITECTURE.md
     * §5.6). The old code treated any `.` next to a hex/colon run as part of a larger
     * token, so `2001:db8::1.` was never a candidate. The fix ignores a `.` that ends
     * the string or is followed by whitespace.
     *
     * Modelled without touching the oracle: each such `.` after a run holding two
     * or more colons is swapped for a [NEUTRAL] character before the oracle runs and
     * swapped back afterwards. No pattern treats [NEUTRAL] as part of a token, so the
     * oracle sees exactly the boundary the fix gives it.
     */
    private fun expected(line: String): String {
        val marked = markSentenceFinalDots(line)
        if (marked == line) return oracleRedact(line)
        return oracleRedact(marked).replace(NEUTRAL, '.')
    }

    private fun markSentenceFinalDots(line: String): String {
        val out = StringBuilder(line)
        var i = 0
        while (i < line.length) {
            if (!isRunChar(line[i])) {
                i++
                continue
            }
            val start = i
            while (i < line.length && isRunChar(line[i])) i++
            val colons = (start until i).count { line[it] == ':' }
            val dotEndsSentence =
                i < line.length &&
                    line[i] == '.' &&
                    (i + 1 == line.length || line[i + 1].isWhitespace() || line[i + 1] == '\u0085')
            if (colons >= 2 && dotEndsSentence) out.setCharAt(i, NEUTRAL)
        }
        return out.toString()
    }

    private fun isRunChar(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || c == ':'

    private companion object {
        /** U+0002: matched by no pattern's token class except `\S`, where it stands in for the `.`. */
        const val NEUTRAL = '\u0002'
    }
}
