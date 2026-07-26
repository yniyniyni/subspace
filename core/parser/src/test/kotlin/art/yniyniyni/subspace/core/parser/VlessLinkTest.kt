// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

private const val UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
private const val PBK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

class VlessLinkTest {
    @Test
    fun `parses a reality link`() {
        val link =
            "vless://$UUID@host.example:443" +
                "?type=tcp&security=reality&sni=www.microsoft.com&pbk=$PBK&sid=ab&fp=chrome&flow=xtls-rprx-vision" +
                "#Tokyo"
        val result = parseVlessLink(link, 0) as LinkResult.Ok
        val out = result.profile.outbound as VlessOutbound

        out.address shouldBe "host.example"
        out.port shouldBe 443
        out.uuid shouldBe UUID
        out.flow shouldBe "xtls-rprx-vision"
        out.stream.network shouldBe "tcp"
        result.profile.name shouldBe "Tokyo"

        val security = out.stream.security as Security.Reality
        security.serverName shouldBe "www.microsoft.com"
        security.publicKey shouldBe PBK
        security.shortId shouldBe "ab"
        security.fingerprint shouldBe "chrome"
        security.spiderX shouldBe "/"
    }

    @Test
    fun `defaults name to host when the fragment is empty`() {
        val result = parseVlessLink("vless://$UUID@host.example:443?type=tcp", 0) as LinkResult.Ok
        result.profile.name shouldBe "host.example"
    }

    @Test
    fun `defaults network to tcp`() {
        val result = parseVlessLink("vless://$UUID@host.example:443", 0) as LinkResult.Ok
        (result.profile.outbound as VlessOutbound).stream.network shouldBe "tcp"
    }

    @Test
    fun `rejects a truncated reality key naming publicKey`() {
        val link = "vless://$UUID@host.example:443?security=reality&pbk=AAEC"
        val result = parseVlessLink(link, 7) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.InvalidRealityKey
        result.failure.index shouldBe 7
    }

    @Test
    fun `rejects a malformed uuid`() {
        val result = parseVlessLink("vless://not-a-uuid@host.example:443", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `rejects an out of range port`() {
        val result = parseVlessLink("vless://$UUID@host.example:70000", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `rejects a link with no host`() {
        val result = parseVlessLink("vless://", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.MalformedUri
    }

    @Test
    fun `parses tls with safe defaults`() {
        val link = "vless://$UUID@host.example:443?security=tls&allowInsecure=1"
        val result = parseVlessLink(link, 0) as LinkResult.Ok
        val security = (result.profile.outbound as VlessOutbound).stream.security as Security.Tls

        security.serverName shouldBe "host.example"
        security.fingerprint shouldBe "chrome"
        security.allowInsecure shouldBe true
    }

    @Test
    fun `uses none for absent or unknown security`() {
        val absent = parseVlessLink("vless://$UUID@host.example:443", 0) as LinkResult.Ok
        val unknown = parseVlessLink("vless://$UUID@host.example:443?security=other", 0) as LinkResult.Ok

        (absent.profile.outbound as VlessOutbound).stream.security shouldBe Security.None
        (unknown.profile.outbound as VlessOutbound).stream.security shouldBe Security.None
    }

    @Test
    fun `failure detail never echoes link or credential`() {
        val link = "vless://$UUID@host.example:443?security=reality&pbk=AAEC"
        val result = parseVlessLink(link, 0) as LinkResult.Bad

        result.failure.detail shouldNotContain link
        result.failure.detail shouldNotContain UUID
        result.failure.detail shouldNotContain "AAEC"
    }

    @Test
    fun `id is stable and ignores display name`() {
        val a = parseVlessLink("vless://$UUID@host.example:443#One", 0) as LinkResult.Ok
        val b = parseVlessLink("vless://$UUID@host.example:443#Two", 0) as LinkResult.Ok

        a.profile.id shouldBe b.profile.id
    }

    @Test
    fun `arbitrary malformed input never throws`() {
        listOf("", "x", "vless://", "vless://@", "vless://host:", "vless://[\u0000").forEach { raw ->
            parseVlessLink(raw, 3) as LinkResult.Bad
        }
    }
}
