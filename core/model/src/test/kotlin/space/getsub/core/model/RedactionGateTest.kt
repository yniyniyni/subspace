// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Pins the gate predicates directly, independent of `redact()`'s round trip.
 *
 * `RedactionOracleTest` cannot see the ICU-specific gap these gates close: the
 * JVM's `java.util.regex` does not do the full Unicode case folding Android's
 * ICU-backed engine does, so `redact("addreß=corp")` and `oracleRedact(...)`
 * agree on the JVM regardless of whether [keyedHostGate] is sound. These tests
 * instead pin the plain-Kotlin boolean logic of the gates themselves, which is
 * platform-independent and does not need ICU to verify.
 */
class RedactionGateTest {
    @Test
    fun `keyedHostGate is true for every non-ASCII case-folding trick`() {
        keyedHostGate("addreß=corp") shouldBe true
        keyedHostGate("ADDREẞ=corp") shouldBe true
        keyedHostGate("hoﬆ=corp") shouldBe true
        keyedHostGate("hoﬅ: corp") shouldBe true
    }

    @Test
    fun `labelledHostGate is true for every non-ASCII case-folding trick`() {
        labelledHostGate("addreß corp") shouldBe true
        labelledHostGate("hoﬆ corp") shouldBe true
        labelledHostGate("hoﬅ: corp") shouldBe true
    }

    @Test
    fun `both gates stay true on plain non-ASCII text with no keyword at all`() {
        // hasNonAscii alone must be enough -- neither gate may depend on a keyword
        // also being present once a non-ASCII character forces the pattern to run.
        keyedHostGate("café") shouldBe true
        labelledHostGate("café") shouldBe true
    }

    @Test
    fun `both gates still skip on ordinary ASCII text with no keyword`() {
        keyedHostGate("plain text with no secrets at all") shouldBe false
        labelledHostGate("plain text with no secrets at all") shouldBe false
    }

    @Test
    fun `hasColonBeforeWhitespace is true before a NEL`() {
        hasColonBeforeWhitespace("corp:\u0085x") shouldBe true
    }

    @Test
    fun `hasColonBeforeWhitespace is still true before ordinary whitespace`() {
        hasColonBeforeWhitespace("vpnserver: connection refused") shouldBe true
    }

    @Test
    fun `hasColonBeforeWhitespace is false with no colon-whitespace pair at all`() {
        hasColonBeforeWhitespace("no colon here") shouldBe false
        hasColonBeforeWhitespace("tcp://no-trailing-space:end") shouldBe false
    }

    // Row 7, Pass 4 (docs/agent/research/2026-09-26-m8.5-row7-release.md): every
    // xray line starts with its own timestamp, which opened the old `contains('.')`
    // gates on every line.
    private val xrayLine =
        "2026/10/03 10:22:25.651837 [Warning] [3909243532] app/proxyman/inbound: " +
            "connection ends > proxy/http: failed to read http request > malformed HTTP request \"ZZZZ BAD\""

    @Test
    fun `hasIpv4Shape needs three dot-digit pairs`() {
        hasIpv4Shape("dial 203.0.113.44:443") shouldBe true
        hasIpv4Shape("1.2.3") shouldBe false
        hasIpv4Shape(xrayLine) shouldBe false
    }

    @Test
    fun `hasIpv4Shape counts Unicode digits, which ICU's backslash-d matches`() {
        hasIpv4Shape("\u0661.\u0662.\u0663.\u0664") shouldBe true
    }

    @Test
    fun `hasHostnameShape needs a dot followed by two ASCII letters`() {
        hasHostnameShape("lookup example.com") shouldBe true
        hasHostnameShape("a.B1 x.Yz") shouldBe true
        hasHostnameShape("a.b1") shouldBe false
        hasHostnameShape(xrayLine) shouldBe false
    }

    /** What the bare-host pass did before the scanner: the regex, run on every input. */
    private fun bareByRegex(line: String): String {
        val pattern = BARE_HOST_PREFIX_PATTERN
        return line.replace(pattern) { match -> replaceHead(match, BARE_TOKEN_GROUP) }
    }

    @Test
    fun `redactBarePrefixes matches the regex on every edge of the token shape`() {
        listOf(
            "vpnserver: connection refused",
            "dial tcp: lookup vpnserver: no such host",
            "a:: b",
            ": b",
            "x :y z",
            "end with colon:",
            "a:\tb",
            "a:\u000Bb c:\u000Cd e:\re f:\ng",
            "\u0001: masked",
            "json: cannot unmarshal",
            "two  spaces:  here",
            "",
            ":",
            "a:b: c",
            "  lead: x",
        ).forEach { line -> redactBarePrefixes(line) shouldBe bareByRegex(line) }
    }

    @Test
    fun `redactBarePrefixes falls back to the regex on non-ASCII input`() {
        // NEL is ICU whitespace but not ASCII: the fallback keeps ICU authoritative.
        redactBarePrefixes("corp:\u0085x") shouldBe bareByRegex("corp:\u0085x")
        redactBarePrefixes("café: down") shouldBe bareByRegex("café: down")
    }
}
