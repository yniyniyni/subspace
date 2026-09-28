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
}
