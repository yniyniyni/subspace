// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.util.Base64

private const val UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

class ShareLinkParserTest {
    @Test
    fun `dispatches vless`() {
        val result = parseShareLink("vless://$UUID@h.example:443", 0) as LinkResult.Ok
        result.profile.outbound.shouldBeInstanceOf<VlessOutbound>()
    }

    @Test
    fun `dispatches trojan`() {
        val result = parseShareLink("trojan://pw@h.example:443", 0) as LinkResult.Ok
        result.profile.outbound.shouldBeInstanceOf<TrojanOutbound>()
    }

    @Test
    fun `dispatches vmess`() {
        val json = "{\"add\":\"vmess.example\",\"port\":443,\"id\":\"$UUID\"}"
        val body = Base64.getEncoder().encodeToString(json.toByteArray())
        val result = parseShareLink("vmess://$body", 0) as LinkResult.Ok
        result.profile.outbound.shouldBeInstanceOf<VmessOutbound>()
    }

    @Test
    fun `dispatches shadowsocks`() {
        val credentials = Base64.getEncoder().encodeToString("aes-256-gcm:synthetic-password".toByteArray())
        val result = parseShareLink("ss://$credentials@ss.example:8388", 0) as LinkResult.Ok
        result.profile.outbound.shouldBeInstanceOf<ShadowsocksOutbound>()
    }

    @Test
    fun `dispatches socks`() {
        val result = parseShareLink("socks://socks.example:1080", 0) as LinkResult.Ok
        result.profile.outbound.shouldBeInstanceOf<SocksOutbound>()
    }

    @Test
    fun `dispatches socks5 alias`() {
        val result = parseShareLink("socks5://socks.example:1080", 0) as LinkResult.Ok
        result.profile.outbound.shouldBeInstanceOf<SocksOutbound>()
    }

    @Test
    fun `scheme matching is case insensitive`() {
        parseShareLink("VLESS://$UUID@h.example:443", 0).shouldBeInstanceOf<LinkResult.Ok>()
    }

    @Test
    fun `leading and trailing whitespace is tolerated`() {
        parseShareLink("  vless://$UUID@h.example:443  ", 0).shouldBeInstanceOf<LinkResult.Ok>()
    }

    @Test
    fun `unknown scheme is a typed failure not an exception`() {
        val result = parseShareLink("ftp://h.example:21", 9) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.UnknownScheme
        result.failure.index shouldBe 9
    }

    @Test
    fun `text with no scheme at all is a failure`() {
        val result = parseShareLink("just some words", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.UnknownScheme
    }
}
