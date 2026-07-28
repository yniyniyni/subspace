// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.util.Base64

class ShadowsocksLinkTest {
    @Test
    fun `parses SIP002 form`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result = parseShadowsocksLink("ss://$credentials@host.example:8388#Paris", 0) as LinkResult.Ok
        val outbound = result.profile.outbound as ShadowsocksOutbound

        outbound shouldBe ShadowsocksOutbound("host.example", 8388, "aes-256-gcm", "s3cret")
        result.profile.name shouldBe "Paris"
    }

    @Test
    fun `parses legacy fully encoded form`() {
        val body = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret@host.example:8388".toByteArray())
        val result = parseShadowsocksLink("ss://$body#Oslo", 0) as LinkResult.Ok
        val outbound = result.profile.outbound as ShadowsocksOutbound

        outbound shouldBe ShadowsocksOutbound("host.example", 8388, "aes-256-gcm", "s3cret")
        result.profile.name shouldBe "Oslo"
    }

    @Test
    fun `legacy form splits password on the last at sign`() {
        val body = Base64.getEncoder().encodeToString("aes-256-gcm:p@ssw@rd@host.example:8388".toByteArray())
        val result = parseShadowsocksLink("ss://$body", 0) as LinkResult.Ok

        (result.profile.outbound as ShadowsocksOutbound).password shouldBe "p@ssw@rd"
    }

    @Test
    fun `accepts URL-safe base64 without padding`() {
        val credentials = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result = parseShadowsocksLink("ss://$credentials@host.example:8388", 0) as LinkResult.Ok

        (result.profile.outbound as ShadowsocksOutbound).method shouldBe "aes-256-gcm"
    }

    @Test
    fun `rejects unsupported method with index`() {
        val credentials = Base64.getEncoder().encodeToString("rot13:s3cret".toByteArray())
        val result = parseShadowsocksLink("ss://$credentials@host.example:8388", 4) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.UnsupportedMethod
        result.failure.index shouldBe 4
    }

    @Test
    fun `rejects garbage as malformed base64`() {
        val result = parseShadowsocksLink("ss://!!!!", 0) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedBase64
    }

    @Test
    fun `rejects an empty password`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:".toByteArray())
        val result = parseShadowsocksLink("ss://$credentials@host.example:8388", 0) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `rejects an invalid port`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result = parseShadowsocksLink("ss://$credentials@host.example:70000", 0) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `rejects non decimal port text`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val authorities =
            listOf(
                "host.example:+8388",
                "host.example:-1",
                "host.example: 8388",
                "host.example:8388 #name",
                "host.example:",
            )

        authorities.forEach { authority ->
            val result = parseShadowsocksLink("ss://$credentials@$authority", 0) as LinkResult.Bad
            result.failure.reason shouldBe ParseFailureReason.MalformedBase64
        }
    }

    @Test
    fun `parses bracketed IPv6 authority and removes brackets from host`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result =
            parseShadowsocksLink("ss://$credentials@[2001:db8::1]:8388", 0) as LinkResult.Ok
        val outbound = result.profile.outbound as ShadowsocksOutbound

        outbound.address shouldBe "2001:db8::1"
        outbound.port shouldBe 8388
    }

    @Test
    fun `rejects unbracketed IPv6 authority`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())

        val result =
            parseShadowsocksLink("ss://$credentials@2001:db8::1:8388", 0) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedBase64
    }

    @Test
    fun `rejects malformed multi colon authority`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val authorities =
            listOf(
                "host:8388:999",
                "[2001:db8::1:8388",
                "[2001:db8::1]",
                "[2001:db8::1]:8388:999",
                "[hostname]:8388",
                "[2001:db8]:8388",
                "[2001:db8::zz]:8388",
                "[1.2.3.4:5.6.7.8::]:8388",
            )

        authorities.forEach { authority ->
            val result = parseShadowsocksLink("ss://$credentials@$authority", 0) as LinkResult.Bad
            result.failure.reason shouldBe ParseFailureReason.MalformedBase64
        }
    }

    @Test
    fun `accepts a Unicode non bracketed hostname`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result = parseShadowsocksLink("ss://$credentials@сервер.example:8388", 0) as LinkResult.Ok

        (result.profile.outbound as ShadowsocksOutbound).address shouldBe "сервер.example"
    }

    @Test
    fun `accepts reg name characters and percent escapes in hostname`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result =
            parseShadowsocksLink("ss://$credentials@host-._~!\$&'()*+,;=%2Eexample:8388", 0) as LinkResult.Ok

        (result.profile.outbound as ShadowsocksOutbound).address shouldBe "host-._~!\$&'()*+,;=%2Eexample"
    }

    @Test
    fun `rejects hostile non bracketed host tokens`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val authorities =
            listOf(
                "host/name:8388",
                "host?name:8388",
                "host#name:8388",
                "host name:8388",
                "host\tname:8388",
                "host\u0000name:8388",
                "host\\name:8388",
                "host|name:8388",
                "host<name>:8388",
                "host%2:8388",
            )

        authorities.forEach { authority ->
            val result = parseShadowsocksLink("ss://$credentials@$authority", 0) as LinkResult.Bad
            result.failure.reason shouldBe ParseFailureReason.MalformedBase64
        }
    }

    @Test
    fun `accepts a final IPv4 tail in bracketed IPv6`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())

        val result =
            parseShadowsocksLink("ss://$credentials@[::ffff:192.0.2.1]:8388", 0) as LinkResult.Ok

        val outbound = result.profile.outbound as ShadowsocksOutbound
        outbound.address shouldBe "::ffff:192.0.2.1"
        outbound.port shouldBe 8388
    }

    @Test
    fun `rejects non canonical IPv4 tails`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val authorities =
            listOf(
                "[::ffff:001.002.003.004]:8388",
                "[::ffff:00.2.3.4]:8388",
                "[::ffff:0.02.3.4]:8388",
            )

        authorities.forEach { authority ->
            val result = parseShadowsocksLink("ss://$credentials@$authority", 0) as LinkResult.Bad
            result.failure.reason shouldBe ParseFailureReason.MalformedBase64
        }
    }

    @Test
    fun `accepts zero IPv4 tail octets`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val result =
            parseShadowsocksLink("ss://$credentials@[::ffff:0.0.0.0]:8388", 0) as LinkResult.Ok

        (result.profile.outbound as ShadowsocksOutbound).address shouldBe "::ffff:0.0.0.0"
    }

    @Test
    fun `decodes percent encoded fragment and falls back to host`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val named = parseShadowsocksLink("ss://$credentials@host.example:8388#Paris%20VPN", 0) as LinkResult.Ok
        val fallback = parseShadowsocksLink("ss://$credentials@host.example:8388#", 0) as LinkResult.Ok

        named.profile.name shouldBe "Paris VPN"
        fallback.profile.name shouldBe "host.example"
    }

    @Test
    fun `prefix matching is case insensitive`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())

        parseShadowsocksLink("SS://$credentials@host.example:8388", 0).shouldBeInstanceOfOk()
    }

    @Test
    fun `id is stable and ignores display name`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:s3cret".toByteArray())
        val first = parseShadowsocksLink("ss://$credentials@host.example:8388#One", 0) as LinkResult.Ok
        val second = parseShadowsocksLink("ss://$credentials@host.example:8388#Two", 0) as LinkResult.Ok

        first.profile.id shouldBe second.profile.id
    }

    @Test
    fun `id changes when method or password changes`() {
        fun parse(
            method: String,
            password: String,
        ): String {
            val credentials = Base64.getEncoder().encodeToString("$method:$password".toByteArray())
            return (parseShadowsocksLink("ss://$credentials@host.example:8388", 0) as LinkResult.Ok).profile.id
        }

        val baseline = parse("aes-256-gcm", "s3cret")
        parse("chacha20-ietf-poly1305", "s3cret") shouldNotBe baseline
        parse("aes-256-gcm", "different") shouldNotBe baseline
    }

    @Test
    fun `arbitrary malformed input never throws and reports a typed container detail`() {
        shouldNotThrowAny {
            val malformed =
                listOf(
                    "ss://not-base64@\u0000:99999#https://secret.example/x",
                    "ss://not-base64@[2001:db8::zz]:8388",
                    "ss://not-base64@[2001:db8::1:8388",
                    "ss://not-base64@secret.example:999999999999",
                    "ss://method:credential@secret.example:8388",
                    "ss://method/credential?secret@secret.example:8388",
                )

            malformed.forEach { input ->
                val result = parseShadowsocksLink(input, 8)
                result.shouldBeInstanceOf<LinkResult.Bad>()
                result.failure.detail shouldBe FailureDetail.Malformed(DetailField.Base64Body)
            }
        }
    }
}

private fun LinkResult.shouldBeInstanceOfOk() {
    (this is LinkResult.Ok) shouldBe true
}
