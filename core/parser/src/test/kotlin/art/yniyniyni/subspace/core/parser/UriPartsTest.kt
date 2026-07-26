// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

class UriPartsTest {
    @Test
    fun `splits a full share link`() {
        val u = parseUri("vLeSs://uuid-here@host.example:443?type=tcp&security=reality#My%20Server")
        u shouldBe
            UriParts(
                scheme = "vless",
                userInfo = "uuid-here",
                host = "host.example",
                port = 443,
                query = mapOf("type" to "tcp", "security" to "reality"),
                fragment = "My Server",
            )
    }

    @Test
    fun `last value wins on duplicate query keys`() {
        parseUri("vless://u@h.example:443?sni=a.example&sni=b.example")?.query?.get("sni") shouldBe "b.example"
    }

    @Test
    fun `percent-decodes the fragment`() {
        parseUri("trojan://pw@h.example:443#Tokyo%20%231")?.fragment shouldBe "Tokyo #1"
    }

    @Test
    fun `preserves literal plus in userinfo and query`() {
        val u = parseUri("trojan://pass+word@h.example:443?key=one+two")
        u?.userInfo shouldBe "pass+word"
        u?.query shouldBe mapOf("key" to "one+two")
    }

    @Test
    fun `decodes encoded plus and spaces without changing invalid escapes`() {
        percentDecode("literal+%2B%20") shouldBe "literal++ "
        percentDecode("%ZZ") shouldBe "%ZZ"
        parseUri("trojan://pw@h.example:443#Tokyo+%2B%20%231")?.fragment shouldBe "Tokyo++ #1"
    }

    @Test
    fun `survives an invalid percent escape in the fragment`() {
        parseUri("trojan://pw@h.example:443#bad%ZZname")?.fragment shouldBe "bad%ZZname"
    }

    @Test
    fun `returns null when there is no host`() {
        parseUri("vless://u@:443") shouldBe null
    }

    @Test
    fun `returns null on a missing port`() {
        parseUri("vless://u@h.example") shouldBe null
    }

    @Test
    fun `handles ipv6 literals`() {
        parseUri("vless://u@[2001:db8::1]:443?type=tcp")?.host shouldBe "2001:db8::1"
    }

    @Test
    fun `empty query and fragment are empty not null`() {
        val u = parseUri("socks://h.example:1080")
        u?.query shouldBe emptyMap()
        u?.fragment shouldBe ""
    }

    @Test
    fun `splits fragment before query and authority`() {
        val u = parseUri("trojan://p@h.example:443?x=1#name?and@more")
        u?.query shouldBe mapOf("x" to "1")
        u?.fragment shouldBe "name?and@more"
    }

    @Test
    fun `splits userinfo on the last at sign`() {
        parseUri("socks://user@credential@h.example:1080")?.userInfo shouldBe "user@credential"
    }

    @Test
    fun `rejects malformed bracketed and extra suffix authorities`() {
        parseUri("vless://u@[2001:db8::1]443") shouldBe null
        parseUri("vless://u@[2001:db8::1]:443:extra") shouldBe null
        parseUri("vless://u@h.example:443:extra") shouldBe null
    }

    @Test
    fun `skips malformed query pairs while preserving valid entries`() {
        parseUri("vless://u@h.example:443?good=value&missing&=no-key&also=ok")?.query shouldBe
            mapOf("good" to "value", "also" to "ok")
    }

    @Test
    fun `arbitrary malformed input never throws`() {
        val inputs = listOf("", "x", "vless://", "vless://@", "vless://h:", "vless://[\u0000")
        inputs.forEach { parseUri(it) }
    }
}
