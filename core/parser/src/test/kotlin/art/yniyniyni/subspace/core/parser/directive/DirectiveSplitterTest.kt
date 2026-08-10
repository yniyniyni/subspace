// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.directive

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.Test

class DirectiveSplitterTest {
    @Test
    fun `reads directives from response headers`() {
        val result =
            DirectiveSplitter.split(
                headers = mapOf("profile-title" to "Name VPN", "profile-update-interval" to "1"),
                body = "vless://uuid@example.com:443",
            )

        result.directives shouldContainExactly
            listOf(
                RawDirective("profile-title", "Name VPN", DirectiveSource.Header),
                RawDirective("profile-update-interval", "1", DirectiveSource.Header),
            )
        result.remainingBody shouldBe "vless://uuid@example.com:443"
    }

    @Test
    fun `reads directives from hash-prefixed body lines and strips them`() {
        val body =
            """
            #profile-title: Name VPN
            #profile-update-interval: 1
            vless://uuid@example.com:443
            trojan://pw@example.org:443
            """.trimIndent()

        val result = DirectiveSplitter.split(headers = emptyMap(), body = body)

        result.directives shouldContainExactly
            listOf(
                RawDirective("profile-title", "Name VPN", DirectiveSource.BodyLine),
                RawDirective("profile-update-interval", "1", DirectiveSource.BodyLine),
            )
        result.remainingBody shouldBe
            "vless://uuid@example.com:443\ntrojan://pw@example.org:443"
    }

    @Test
    fun `header wins over a body line for the same key`() {
        val result =
            DirectiveSplitter.split(
                headers = mapOf("profile-update-interval" to "24"),
                body = "#profile-update-interval: 1\nvless://uuid@example.com:443",
            )

        result.directives shouldContainExactly
            listOf(
                RawDirective("profile-update-interval", "24", DirectiveSource.Header),
            )
    }

    @Test
    fun `keys are matched case-insensitively and normalised to lower case`() {
        val result =
            DirectiveSplitter.split(
                headers = mapOf("Profile-Title" to "Name VPN"),
                body = "",
            )

        result.directives shouldContainExactly
            listOf(
                RawDirective("profile-title", "Name VPN", DirectiveSource.Header),
            )
    }

    @Test
    fun `a hash line without a colon is not a directive and stays in the body`() {
        // A bare "#comment" is not key: value. Treating it as a directive with an
        // empty value would put a junk row in front of the validator for every
        // provider who comments their template.
        val result = DirectiveSplitter.split(emptyMap(), "#just a comment\nvless://u@h:443")

        result.directives.shouldBeEmpty()
        result.remainingBody shouldBe "#just a comment\nvless://u@h:443"
    }

    @Test
    fun `a fragment in a share link is not mistaken for a directive`() {
        // vless://...#My%20Server — the '#' is mid-line, not line-leading.
        val body = "vless://uuid@example.com:443#My%20Server"
        val result = DirectiveSplitter.split(emptyMap(), body)

        result.directives.shouldBeEmpty()
        result.remainingBody shouldBe body
    }

    @Test
    fun `values are trimmed but internal spacing is preserved`() {
        val result = DirectiveSplitter.split(emptyMap(), "#profile-title:   Name  VPN   ")

        result.directives shouldContainExactly
            listOf(
                RawDirective("profile-title", "Name  VPN", DirectiveSource.BodyLine),
            )
    }

    @Test
    fun `a duplicated body key keeps the first occurrence`() {
        val result =
            DirectiveSplitter.split(
                emptyMap(),
                "#profile-title: First\n#profile-title: Second",
            )

        result.directives shouldContainExactly
            listOf(
                RawDirective("profile-title", "First", DirectiveSource.BodyLine),
            )
    }
}
