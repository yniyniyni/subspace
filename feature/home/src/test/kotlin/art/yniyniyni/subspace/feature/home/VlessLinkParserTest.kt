// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.Security
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The parser is throwaway (M2 replaces it), but the tests are not optional: it
 * is the only thing standing between a pasted string and the tunnel, and §7's
 * "never throw" rule is what keeps a bad paste from crashing the app.
 */
class VlessLinkParserTest {
    private val realityLink =
        "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@example.com:443" +
            "?type=tcp&security=reality&sni=www.microsoft.com" +
            "&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8" +
            "&sid=0123abcd&fp=chrome&spx=%2F&flow=xtls-rprx-vision#My%20Server"

    @Test
    fun `parses a reality link`() {
        val profile = VlessLinkParser.parse(realityLink)
        profile.shouldNotBeNull()

        profile.name shouldBe "My Server"
        profile.outbound.address shouldBe "example.com"
        profile.outbound.port shouldBe 443
        profile.outbound.uuid shouldBe "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
        profile.outbound.flow shouldBe "xtls-rprx-vision"
        profile.outbound.stream.network shouldBe "tcp"

        val reality = profile.outbound.stream.security as Security.Reality
        reality.serverName shouldBe "www.microsoft.com"
        reality.publicKey shouldBe "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        reality.shortId shouldBe "0123abcd"
        reality.fingerprint shouldBe "chrome"
        // %2F must be decoded, or Xray receives a literal "%2F" as spiderX.
        reality.spiderX shouldBe "/"
    }

    @Test
    fun `parses a tls link`() {
        val link = "vless://uuid-here@example.com:443?security=tls&sni=example.com&fp=firefox#T"
        val security = VlessLinkParser.parse(link)?.outbound?.stream?.security
        (security as Security.Tls).fingerprint shouldBe "firefox"
    }

    @Test
    fun `falls back to the host when there is no fragment`() {
        val link = "vless://uuid-here@example.com:443?security=none"
        VlessLinkParser.parse(link)?.name shouldBe "example.com"
    }

    @Test
    fun `defaults the network to tcp when absent`() {
        val link = "vless://uuid-here@example.com:443?security=none#X"
        VlessLinkParser.parse(link)?.outbound?.stream?.network shouldBe "tcp"
    }

    @Test
    fun `last value wins on a duplicate query key`() {
        // §7: real subscriptions carry duplicate keys. Whatever we do must be a
        // decision, not an accident of map construction.
        val link = "vless://uuid-here@example.com:443?security=tls&sni=first.com&sni=second.com#X"
        val tls = VlessLinkParser.parse(link)?.outbound?.stream?.security as Security.Tls
        tls.serverName shouldBe "second.com"
    }

    @Test
    fun `returns null rather than throwing on malformed input`() {
        // §7: never throw. Each of these is something a user can genuinely paste.
        listOf(
            "",
            "   ",
            "not a link at all",
            "https://example.com",
            "vless://",
            "vless://@example.com:443",
            "vless://uuid@:443",
            "vless://uuid@example.com",
            "vless://uuid@example.com:notaport",
            "vless://uuid@example.com:0",
            "vless://uuid@example.com:443?%%%broken=%",
            "vmess://something",
        ).forEach { input ->
            VlessLinkParser.parse(input) shouldBe null
        }
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        // Pasting from a chat client routinely brings a newline along.
        VlessLinkParser.parse("  $realityLink\n").shouldNotBeNull()
    }

    @Test
    fun `treats an unknown security value as none`() {
        val link = "vless://uuid-here@example.com:443?security=quantum#X"
        VlessLinkParser.parse(link)?.outbound?.stream?.security shouldBe Security.None
    }
}
