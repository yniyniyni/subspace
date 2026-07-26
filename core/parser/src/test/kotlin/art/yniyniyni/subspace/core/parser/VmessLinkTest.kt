// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.VmessOutbound
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

private const val VMESS_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

private fun vmessLink(json: String): String = "vmess://" + Base64.getEncoder().encodeToString(json.toByteArray())

private fun okOutbound(json: String): VmessOutbound =
    (parseVmessLink(vmessLink(json), 0) as LinkResult.Ok).profile.outbound as VmessOutbound

class VmessLinkTest {
    @Test
    fun `parses a standard v2 body`() {
        val json =
            """{"v":"2","ps":"Osaka","add":"host.example","port":"443",""" +
                """"id":"$VMESS_UUID","aid":"0","scy":"auto","net":"ws","tls":"tls","sni":"a.example"}"""
        val result = parseVmessLink(vmessLink(json), 0) as LinkResult.Ok
        val out = result.profile.outbound as VmessOutbound

        out.address shouldBe "host.example"
        out.port shouldBe 443
        out.uuid shouldBe VMESS_UUID
        out.alterId shouldBe 0
        out.security shouldBe "auto"
        out.stream.network shouldBe "ws"
        out.stream.security shouldBe Security.Tls("a.example", "chrome", false)
        result.profile.name shouldBe "Osaka"
    }

    @Test
    fun `accepts numeric port and aid`() {
        val json =
            """{"v":2,"ps":"N","add":"host.example","port":443,"id":"$VMESS_UUID","aid":3}"""
        val out = okOutbound(json)

        out.port shouldBe 443
        out.alterId shouldBe 3
    }

    @Test
    fun `defaults security to auto when scy is absent`() {
        val json = """{"ps":"N","add":"host.example","port":"443","id":"$VMESS_UUID"}"""

        okOutbound(json).security shouldBe "auto"
    }

    @Test
    fun `rejects a body that is not base64`() {
        val result = parseVmessLink("vmess://!!!!", 3) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedBase64
        result.failure.index shouldBe 3
        result.failure.detail shouldBe "vmess body is not base64"
    }

    @Test
    fun `rejects base64 that is not json`() {
        val result =
            parseVmessLink(
                "vmess://" + Base64.getEncoder().encodeToString("hello".toByteArray()),
                0,
            ) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `rejects a missing uuid`() {
        val json = """{"ps":"N","add":"host.example","port":"443"}"""

        val result = parseVmessLink(vmessLink(json), 0) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `rejects malformed json object`() {
        val result = parseVmessLink(vmessLink("{"), 4) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedJson
        result.failure.index shouldBe 4
    }

    @Test
    fun `rejects a json array`() {
        val result = parseVmessLink(vmessLink("[]"), 0) as LinkResult.Bad

        result.failure.reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `rejects blank required and uses address for blank display name`() {
        val missingAddress =
            parseVmessLink(vmessLink("""{"add":" ","port":443,"id":"$VMESS_UUID"}"""), 0)
                as LinkResult.Bad
        missingAddress.failure.reason shouldBe ParseFailureReason.MalformedUri

        val out = okOutbound("""{"ps":" ","add":"host.example","port":443,"id":"$VMESS_UUID"}""")
        val result =
            parseVmessLink(vmessLink("""{"ps":" ","add":"host.example","port":443,"id":"$VMESS_UUID"}"""), 0) as
                LinkResult.Ok

        out.stream.network shouldBe "tcp"
        out.stream.security shouldBe Security.None
        result.profile.name shouldBe "host.example"
    }

    @Test
    fun `tls uses host fallback and fp fallback`() {
        val json = """{"add":"host.example","host":"sni.example","port":443,"id":"$VMESS_UUID","tls":"tls"}"""
        val out = okOutbound(json)

        out.stream.security shouldBe Security.Tls("sni.example", "chrome", false)
    }

    @Test
    fun `arbitrary malformed input never throws`() {
        shouldNotThrowAny {
            parseVmessLink("vmess://\u0000\u0001not-json", -1)
        }
    }
}
