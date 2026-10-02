// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import space.getsub.core.model.redact

/**
 * M8.5 Task 13's deferred Important 2(c): `redact()`'s case-folding and
 * Unicode-linebreak gates are written against Java's regex engine on the JVM
 * unit tests, but ship on Android's ICU-backed regex implementation. This is
 * a throwaway on-device proof that the same inputs are still caught there.
 *
 * Kept only if it passes cleanly (Task 19 controller addendum §E); a failure
 * here is the CRITICAL finding that ICU diverges from the JVM oracle and is
 * reported rather than fixed by this probe.
 */
@RunWith(AndroidJUnit4::class)
class IcuRedactionProbeTest {
    @Test
    fun icuCaseFoldingCatchesSharpSAddress() {
        val out = redact("addreß=corp")
        assertFalse("ICU case-folding gate did not fire: $out", out.contains("corp"))
    }

    @Test
    fun icuCaseFoldingCatchesLigatureHost() {
        val out = redact("hoﬆ corp")
        assertFalse("ICU case-folding gate did not fire: $out", out.contains("corp"))
    }

    @Test
    fun icuNelLineBreakCatchesKeyedHost() {
        val out = redact("vpnserver:\u0085refused")
        assertFalse("ICU NEL line-break gate did not fire: $out", out.contains("vpnserver"))
    }

    /**
     * M8.5 spec §3.2, Task 21: `redactIpv6Candidates` replaced `IPV6_PATTERN`'s
     * whole-string regex scan with a hand-written maximal-run finder that only
     * calls `IPV6_PATTERN.matches(run)` on the isolated candidate substring. The
     * equivalence argument in `Redaction.kt`'s KDoc depends on that single
     * `matches()` call seeing the same lookbehind/lookahead semantics ICU gives
     * the old `replace()` call — this is the on-device check for that, not just
     * the JVM oracle in `RedactionOracleTest`.
     *
     * Fix round 1 (review, Important 1): the input must be something *only*
     * the IPv6 pass can catch, and the assertion must pin the exact output.
     * The original version of this test used `"dial udp [2001:db8::42]:53: ..."`
     * and only asserted the address was gone — but `"dial"` is a
     * `LABELLED_HOST_PATTERN` keyword, so that pass alone (with `"udp"` as the
     * optional protocol word) swallows the whole bracketed `[2001:db8::42]:53:`
     * token regardless of whether the IPv6 pass runs at all. That test would
     * still pass with `redactIpv6Candidates` deleted outright, which is not a
     * proof of anything. `"peer"` is not a `KEYED_HOST_PATTERN` or
     * `LABELLED_HOST_PATTERN` keyword, the address has no trailing
     * colon-before-whitespace for `BARE_HOST_PREFIX_PATTERN` to catch, and
     * nothing else in the pipeline can match a bare colon-delimited hex run —
     * so an exact-output match here is only possible if the IPv6 candidate
     * scan itself fired. Verified on the JVM (`RedactionTest`) that
     * `redact()` actually produces this exact string before pinning it here.
     */
    @Test
    fun icuIpv6CandidateScanMatchesTheWholeRun() {
        val out = redact("peer 2001:db8::42 closed")
        assertEquals("peer <redacted> closed", out)
    }

    /**
     * The candidate scan's dot-boundary check (a hex/colon run immediately
     * adjacent to `.` is never a candidate, mirroring `IPV6_PATTERN`'s own
     * `(?<![0-9A-Fa-f:.])` / `(?!...)` classes) must hold under ICU too, not
     * only under the JVM's `java.util.regex`. Pins the exact, unchanged output
     * (Fix round 1, review Important 1) rather than only the absence of
     * `"<redacted>"`, which a passing-by-accident test could also satisfy for
     * the wrong reason. Verified on the JVM (`RedactionTest`) that `redact()`
     * actually leaves this line untouched before pinning it here.
     */
    @Test
    fun icuIpv6CandidateScanRespectsTheDotBoundary() {
        val out = redact("build 12:34:ab.5 end")
        assertEquals("build 12:34:ab.5 end", out)
    }
}
