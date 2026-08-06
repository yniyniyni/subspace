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
}
