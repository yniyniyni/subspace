// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.directive

import io.kotest.matchers.shouldBe
import org.junit.Test

class DirectiveKindTest {
    private fun canonical(
        kind: DirectiveKind,
        value: String,
    ): String? = (kind.canonicalise(value) as? KindResult.Canonical)?.value

    private fun reason(
        kind: DirectiveKind,
        value: String,
    ): RejectionReason? = (kind.canonicalise(value) as? KindResult.Invalid)?.reason

    @Test
    fun `true and 1 enable a boolean`() {
        canonical(DirectiveKind.Bool, "true") shouldBe "true"
        canonical(DirectiveKind.Bool, "TRUE") shouldBe "true"
        canonical(DirectiveKind.Bool, "1") shouldBe "true"
    }

    @Test
    fun `any other non-empty value disables a boolean`() {
        // ARCHITECTURE.md §A.1, and the rule most likely to be written
        // backwards: it is NOT "false disables", it is "anything unrecognised
        // disables". A typo in a provider's template must not turn a feature on.
        listOf("false", "0", "no", "yes", "off", "on", "enabled", "誤", "-1")
            .forEach { canonical(DirectiveKind.Bool, it) shouldBe "false" }
    }

    @Test
    fun `a blank boolean is rejected rather than read as disabled`() {
        // "the header is absent" and "the header is present and empty" are
        // different provider mistakes; only the second is worth reporting.
        reason(DirectiveKind.Bool, "") shouldBe RejectionReason.BlankValue
        reason(DirectiveKind.Bool, "   ") shouldBe RejectionReason.BlankValue
    }

    @Test
    fun `an integer inside its range canonicalises`() {
        val kind = DirectiveKind.Integer(min = 1, max = 8760)
        canonical(kind, "1") shouldBe "1"
        canonical(kind, "24") shouldBe "24"
        canonical(kind, " 6 ") shouldBe "6"
    }

    @Test
    fun `an integer outside its range is rejected, never clamped`() {
        // Spec §8: a provider sending 0 or a negative must be rejected, not
        // clamped, or a hostile subscription has configured a tight-loop
        // refresher and the clamp hid it.
        val kind = DirectiveKind.Integer(min = 1, max = 8760)
        reason(kind, "0") shouldBe RejectionReason.OutOfRange
        reason(kind, "-1") shouldBe RejectionReason.OutOfRange
        reason(kind, "999999") shouldBe RejectionReason.OutOfRange
    }

    @Test
    fun `a non-integer is rejected`() {
        val kind = DirectiveKind.Integer(min = 1, max = 8760)
        reason(kind, "1.5") shouldBe RejectionReason.NotAnInteger
        reason(kind, "one") shouldBe RejectionReason.NotAnInteger
        reason(kind, "") shouldBe RejectionReason.BlankValue
    }

    @Test
    fun `an integer too large for Int does not overflow into range`() {
        // "99999999999999999999".toIntOrNull() is null, not a wrapped value.
        val kind = DirectiveKind.Integer(min = 1, max = 8760)
        reason(kind, "99999999999999999999") shouldBe RejectionReason.NotAnInteger
    }

    @Test
    fun `text within its length limit canonicalises`() {
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)
        canonical(kind, "Name VPN") shouldBe "Name VPN"
    }

    @Test
    fun `base64 text is decoded when the kind allows it`() {
        // profile-title is documented as "plain or base64".
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)
        canonical(kind, "TmFtZSBWUE4=") shouldBe "Name VPN"
    }

    @Test
    fun `the length limit applies to the decoded value`() {
        // A 25-char cap on the encoded form would let a 40-char title through.
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)
        val long = "This title is far longer than twenty-five characters"
        val encoded =
            java.util.Base64
                .getEncoder()
                .encodeToString(long.toByteArray())
        reason(kind, encoded) shouldBe RejectionReason.TooLong
    }

    @Test
    fun `text that merely looks like base64 is kept as plain text`() {
        // "VPN" decodes to bytes that are not valid UTF-8 text; a provider
        // sending a short plain name must not have it mangled.
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)
        canonical(kind, "Fast VPN") shouldBe "Fast VPN"
    }

    @Test
    fun `an enum member canonicalises to lower case`() {
        val kind = DirectiveKind.Enumerated(setOf("proxy", "proxy-head", "tcp"))
        canonical(kind, "TCP") shouldBe "tcp"
        reason(kind, "icmp") shouldBe RejectionReason.NotAnEnumMember
    }

    @Test
    fun `an http or https url canonicalises and anything else is rejected`() {
        canonical(DirectiveKind.Url, "https://example.com/sub") shouldBe
            "https://example.com/sub"
        reason(DirectiveKind.Url, "javascript:alert(1)") shouldBe
            RejectionReason.MalformedUrl
        reason(DirectiveKind.Url, "file:///etc/passwd") shouldBe
            RejectionReason.MalformedUrl
        reason(DirectiveKind.Url, "not a url") shouldBe RejectionReason.MalformedUrl
    }

    @Test
    fun `csv values are trimmed and empty entries dropped`() {
        canonical(DirectiveKind.Csv, "a.b.c,  d.e.f , ,g") shouldBe "a.b.c,d.e.f,g"
        reason(DirectiveKind.Csv, " , , ") shouldBe RejectionReason.BlankValue
    }

    // The `base64:` marker. Every profile-title and announce Remnawave sends carries it
    // (getUserProfileHeadersInfo builds both as `base64:${...}`), and M4's device run found the
    // whole prefix going undecoded. The fixtures below are the real values from that run.

    @Test
    fun `the base64 marker prefix is stripped before decoding`() {
        // "🔐 Trust VPN" — 27 characters encoded, which overran profile-title's 25-character
        // budget and was rejected as TooLong, so the group kept its hostname fallback. Decoded
        // it is 12 UTF-16 units, comfortably inside the same budget.
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)

        canonical(kind, "base64:8J+UkCBUcnVzdCBWUE4=") shouldBe "🔐 Trust VPN"
    }

    @Test
    fun `a marked value is measured after decoding, not before`() {
        // The budget applies to what the user reads. 27 > 25 > 12.
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)

        "base64:8J+UkCBUcnVzdCBWUE4=".length shouldBe 27
        canonical(kind, "base64:8J+UkCBUcnVzdCBWUE4=")!!.length shouldBe 12
    }

    @Test
    fun `the marker is recognised whatever its casing`() {
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)

        canonical(kind, "BASE64:8J+UkCBUcnVzdCBWUE4=") shouldBe "🔐 Trust VPN"
    }

    @Test
    fun `a multi-line announce decodes rather than being stored as a blob`() {
        // Stored as a 47-character `base64:…` blob before this fix — 7 for the marker plus 40 of
        // payload, which is exactly the length the device's database showed.
        val kind = DirectiveKind.Text(maxLength = 200, base64Allowed = true)
        val encoded = "base64:TmV3IEVyYSDwn4yNClN0YXkgVHJ1c3Qg8J+Ukg=="

        encoded.length shouldBe 47
        canonical(kind, encoded) shouldBe "New Era 🌍\nStay Trust 🔒"
    }

    @Test
    fun `a value claiming to be base64 but isn't is rejected, not kept literally`() {
        // Keeping it would store the marker itself — the defect this pair of tests exists for.
        val kind = DirectiveKind.Text(maxLength = 200, base64Allowed = true)

        reason(kind, "base64:!!!not valid!!!") shouldBe RejectionReason.NotBase64
    }

    @Test
    fun `an unmarked plain-text title still survives unchanged`() {
        // Without the marker the decode stays a heuristic, so the fallback must still hold.
        val kind = DirectiveKind.Text(maxLength = 25, base64Allowed = true)

        canonical(kind, "Trust VPN") shouldBe "Trust VPN"
    }
}
