// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser.directive

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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

    // M6 Task 16. A bare deeplink is how the routing directive can arrive in a
    // body — it carries no `#key:` prefix, so until now it fell straight
    // through to the share-link parser, which would try to read it as a server.
    @Test
    fun `a bare routing link in the body is consumed as a directive`() {
        val body =
            """
            #profile-title: NameVPN
            happ://routing/add/eyJOYW1lIjoiUCJ9
            vless://uuid@host:443?type=tcp#Server
            """.trimIndent()

        val result = DirectiveSplitter.split(emptyMap(), body)

        result.directives.map { it.key } shouldContain "routing"
        // The remaining body handed onward must not contain it (§A.1).
        result.remainingBody.contains("happ://routing") shouldBe false
        result.remainingBody.contains("vless://") shouldBe true
    }

    @Test
    fun `a subspace scheme routing link is consumed too`() {
        val result = DirectiveSplitter.split(emptyMap(), "subspace://routing/off")

        result.directives.map { it.key } shouldContain "routing"
        result.remainingBody.isBlank() shouldBe true
    }

    // Header wins over body (§A.1), and the body line is still consumed rather
    // than left for the share-link parser to choke on.
    @Test
    fun `a routing header wins over a body link and still consumes it`() {
        val result =
            DirectiveSplitter.split(
                mapOf("routing" to "happ://routing/add/fromheader"),
                "happ://routing/add/frombody\nvless://uuid@host:443#Server",
            )

        result.directives.single { it.key == "routing" } shouldBe
            RawDirective("routing", "happ://routing/add/fromheader", DirectiveSource.Header)
        result.remainingBody.contains("happ://routing") shouldBe false
    }

    // A share link's fragment is a server name. Consuming a mid-line match
    // would destroy it, the same trap asBodyDirective's own KDoc documents.
    @Test
    fun `a routing link inside a share link fragment is left alone`() {
        val body = "vless://uuid@host:443?type=tcp#happ://routing/add/x"

        val result = DirectiveSplitter.split(emptyMap(), body)

        result.directives.shouldBeEmpty()
        result.remainingBody shouldBe body
    }

    // Only `routing/` under a supported scheme. A profile share link is not a
    // routing directive and must reach the share-link parser intact.
    @Test
    fun `a non-routing happ link stays in the body`() {
        val body = "happ://add/vless://uuid@host:443"

        val result = DirectiveSplitter.split(emptyMap(), body)

        result.directives.shouldBeEmpty()
        result.remainingBody shouldBe body
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
