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
}
