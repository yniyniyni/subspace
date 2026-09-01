// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser.directive

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test
import io.kotest.matchers.maps.shouldContainExactly as mapShouldContainExactly

class DirectiveValidatorTest {
    private fun header(
        key: String,
        value: String,
    ) = RawDirective(key, value, DirectiveSource.Header)

    @Test
    fun `accepted directives canonicalise into the accepted map`() {
        val result =
            DirectiveValidator.validate(
                listOf(
                    header("profile-title", "TmFtZSBWUE4="),
                    header("profile-update-interval", "6"),
                    header("sniffing-enable", "1"),
                ),
            )

        result.accepted.mapShouldContainExactly(
            mapOf(
                "profile-title" to "Name VPN",
                "profile-update-interval" to "6",
                "sniffing-enable" to "true",
            ),
        )
        result.rejections.shouldBeEmpty()
    }

    @Test
    fun `an unknown key is rejected and never reaches accepted`() {
        val result = DirectiveValidator.validate(listOf(header("totally-made-up", "1")))

        result.accepted.isEmpty() shouldBe true
        result.rejections shouldContainExactly
            listOf(
                DirectiveRejection("totally-made-up", RejectionReason.UnknownKey),
            )
    }

    @Test
    fun `a cut key is rejected as CutKey, distinctly from unknown`() {
        // The distinction matters: "we have never heard of this" and "we
        // deliberately do not honour this" are different diagnostics (§10.4).
        val result = DirectiveValidator.validate(listOf(header("hide-settings", "true")))

        result.rejections shouldContainExactly
            listOf(
                DirectiveRejection("hide-settings", RejectionReason.CutKey),
            )
    }

    @Test
    fun `a schema violation rejects only that key and keeps the rest`() {
        // §7's never-throw philosophy applied to directives: one bad value must
        // not lose the other directives in the same response.
        val result =
            DirectiveValidator.validate(
                listOf(
                    header("profile-title", "Name VPN"),
                    header("profile-update-interval", "0"),
                    header("sniffing-enable", "true"),
                ),
            )

        // .toList() on both sides: kotest's shouldContainExactly refuses to
        // compare a Map.keys Set (a java.util.LinkedHashMap$LinkedKeySet,
        // which is a Set but not a java.util.LinkedHashSet) against a List —
        // see io.kotest.assertions.eq.IterableEq. Not a validator bug.
        result.accepted.keys.toList() shouldContainExactly
            listOf(
                "profile-title",
                "sniffing-enable",
            ).toSet().toList()
        result.rejections shouldContainExactly
            listOf(
                DirectiveRejection("profile-update-interval", RejectionReason.OutOfRange),
            )
    }

    @Test
    fun `a rejection never carries the rejected value`() {
        // §5.6. DirectiveRejection has no value field at all, so this is a
        // structural guarantee — the test pins it against a future widening.
        val secret = "https://evil.example/steal?token=SUPERSECRET"
        val result = DirectiveValidator.validate(listOf(header("new-url", "not-a-url")))

        result.rejections
            .single()
            .toString()
            .contains("not-a-url") shouldBe false
        DirectiveValidator
            .validate(listOf(header("unknown-key", secret)))
            .rejections
            .single()
            .toString()
            .contains("SUPERSECRET") shouldBe false
    }

    @Test
    fun `validation never throws on any input`() {
        listOf("", "   ", "\u0000", "\uFEFF", "a".repeat(100_000))
            .forEach { value ->
                DirectiveValidator.validate(listOf(header("profile-title", value)))
                DirectiveValidator.validate(listOf(header(value, value)))
            }
    }

    @Test
    fun `a duplicate key keeps the first and does not double-report`() {
        val result =
            DirectiveValidator.validate(
                listOf(header("profile-update-interval", "6"), header("profile-update-interval", "12")),
            )

        result.accepted["profile-update-interval"] shouldBe "6"
        result.rejections.shouldBeEmpty()
    }
}
