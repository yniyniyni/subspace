// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import androidx.test.ext.junit.runners.AndroidJUnit4
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
     * the old `replace()` call — this proves it on-device, not just on the JVM
     * oracle in `RedactionOracleTest`.
     */
    @Test
    fun icuIpv6CandidateScanMatchesTheWholeRun() {
        val out = redact("dial udp [2001:db8::42]:53: i/o timeout")
        assertFalse("ICU candidate scan did not redact a real IPv6 address: $out", out.contains("2001:db8"))
    }

    /**
     * The candidate scan's dot-boundary check (a hex/colon run immediately
     * adjacent to `.` is never a candidate, mirroring `IPV6_PATTERN`'s own
     * `(?<![0-9A-Fa-f:.])` / `(?!...)` classes) must hold under ICU too, not
     * only under the JVM's `java.util.regex`.
     */
    @Test
    fun icuIpv6CandidateScanRespectsTheDotBoundary() {
        val out = redact("build 12:34:ab.5 end")
        assertFalse(
            "ICU dot-boundary check redacted a run IPV6_PATTERN's own lookaround would reject: $out",
            out.contains("<redacted>"),
        )
    }
}
