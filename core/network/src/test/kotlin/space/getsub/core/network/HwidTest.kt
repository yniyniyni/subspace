// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class HwidTest {
    // remnawave/panel docs/features/hwid-device-limit.md, fetched 2026-08-06:
    // "the HWID must be 10 to 64 characters long and may only contain Latin
    // letters, digits, = and -."
    private val panelPattern = Regex("^[a-zA-Z0-9=-]{10,64}$")

    @Test
    fun `a derived hwid matches the panel's required pattern`() {
        panelPattern.matches(deriveHwid("0123456789abcdef")) shouldBe true
    }

    @Test
    fun `the pattern holds for every plausible ANDROID_ID shape`() {
        listOf(
            "0123456789abcdef",
            "FFFFFFFFFFFFFFFF",
            "",
            "9774d56d682e549c",
            "a".repeat(256),
        ).forEach { panelPattern.matches(deriveHwid(it)) shouldBe true }
    }

    @Test
    fun `derivation is stable for the same input`() {
        deriveHwid("0123456789abcdef") shouldBe deriveHwid("0123456789abcdef")
    }

    @Test
    fun `different devices derive different hwids`() {
        deriveHwid("0123456789abcdef") shouldNotBe deriveHwid("fedcba9876543210")
    }

    @Test
    fun `the raw ANDROID_ID never appears in the derived value`() {
        // §A.4.1: hash before sending so the raw platform ID never leaves the
        // device.
        val androidId = "0123456789abcdef"
        deriveHwid(androidId).contains(androidId) shouldBe false
    }

    @Test
    fun `the derived value uses base64url, never standard base64`() {
        // Standard base64's '+' and '/' are illegal per the panel pattern, and
        // padding '=' is legal but pointless. 1000 inputs is enough to hit both
        // characters if the wrong encoder is used.
        (0 until 1000).forEach { i ->
            val derived = deriveHwid("device-$i")
            derived.contains('+') shouldBe false
            derived.contains('/') shouldBe false
        }
    }

    @Test
    fun `the derived value is 43 characters`() {
        // Unpadded base64url of SHA-256. Recorded so a switch to hex (64 chars,
        // exactly at the panel's maximum with no headroom) is a deliberate
        // change rather than an accident.
        deriveHwid("0123456789abcdef").length shouldBe 43
    }
}
