// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.SocksOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.util.Base64

class SocksLinkTest {
    @Test
    fun `parses socks with no credentials`() {
        val result = parseSocksLink("socks://host.example:1080#Local", 0) as LinkResult.Ok
        val out = result.profile.outbound as SocksOutbound

        out.address shouldBe "host.example"
        out.port shouldBe 1080
        out.username shouldBe null
        out.password shouldBe null
        result.profile.name shouldBe "Local"
    }

    @Test
    fun `parses plain user colon pass credentials`() {
        val result = parseSocksLink("socks://alice:secret@host.example:1080", 0) as LinkResult.Ok
        val out = result.profile.outbound as SocksOutbound

        out.username shouldBe "alice"
        out.password shouldBe "secret"
    }

    @Test
    fun `plain password may contain colons`() {
        val result = parseSocksLink("socks://alice:one:two@host.example:1080", 0) as LinkResult.Ok
        val out = result.profile.outbound as SocksOutbound

        out.username shouldBe "alice"
        out.password shouldBe "one:two"
    }

    @Test
    fun `parses base64 encoded credentials`() {
        val creds = Base64.getEncoder().encodeToString("alice:secret".toByteArray())
        val result = parseSocksLink("socks://$creds@host.example:1080", 0) as LinkResult.Ok
        val out = result.profile.outbound as SocksOutbound

        out.username shouldBe "alice"
        out.password shouldBe "secret"
    }

    @Test
    fun `parses url safe unpadded base64 credentials`() {
        val result = parseSocksLink("socks://YTp-@host.example:1080", 0) as LinkResult.Ok
        val out = result.profile.outbound as SocksOutbound

        out.username shouldBe "a"
        out.password shouldBe "~"
    }

    @Test
    fun `empty credential components become null`() {
        val result = parseSocksLink("socks://:secret@host.example:1080", 0) as LinkResult.Ok
        val out = result.profile.outbound as SocksOutbound

        out.username shouldBe null
        out.password shouldBe "secret"
    }

    @Test
    fun `rejects undecodable encoded credentials`() {
        val result = parseSocksLink("socks://%%%@host.example:1080", 4) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedBase64
        result.failure.detail shouldBe FailureDetail.Malformed(DetailField.Base64Body)
    }

    @Test
    fun `rejects decoded credentials without a separator`() {
        val encoded = Base64.getEncoder().encodeToString("alice".toByteArray())
        val result = parseSocksLink("socks://$encoded@host.example:1080", 4) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MissingCredential
        result.failure.detail shouldBe FailureDetail.Malformed(DetailField.Credential)
    }

    @Test
    fun `profile id includes password`() {
        val first = parseSocksLink("socks://alice:first@host.example:1080", 0) as LinkResult.Ok
        val second = parseSocksLink("socks://alice:second@host.example:1080", 0) as LinkResult.Ok

        first.profile.id shouldNotBe second.profile.id
    }

    @Test
    fun `password only identity differs from anonymous identity`() {
        val passwordOnly = parseSocksLink("socks://:secret@host.example:1080", 0) as LinkResult.Ok
        val anonymous = parseSocksLink("socks://host.example:1080", 0) as LinkResult.Ok

        passwordOnly.profile.id shouldNotBe anonymous.profile.id
    }

    @Test
    fun `identical profiles have stable identity`() {
        val first = parseSocksLink("socks://alice:secret@host.example:1080#One", 0) as LinkResult.Ok
        val second = parseSocksLink("socks://alice:secret@host.example:1080#Two", 0) as LinkResult.Ok

        first.profile.id shouldBe second.profile.id
    }

    @Test
    fun `rejects a bad port`() {
        val result = parseSocksLink("socks://host.example:99999", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `malformed input never throws and failures do not echo credentials`() {
        listOf("", "x", "socks://", "socks://host:", "socks://[\u0000")
            .forEach { raw ->
                val result = parseSocksLink(raw, 3) as LinkResult.Bad
                result.failure.detail shouldBe FailureDetail.Malformed(DetailField.Uri)
            }
    }
}
