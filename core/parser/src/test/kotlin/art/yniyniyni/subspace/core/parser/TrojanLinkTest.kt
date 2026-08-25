// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

class TrojanLinkTest {
    @Test
    fun `parses a trojan link`() {
        val result =
            parseTrojanLink(
                "trojan://s3cret@host.example:443?sni=a.example#Berlin",
                0,
            ) as LinkResult.Ok
        val out = result.profile.outbound as TrojanOutbound

        out.address shouldBe "host.example"
        out.port shouldBe 443
        out.password shouldBe "s3cret"
        result.profile.name shouldBe "Berlin"
        (out.stream.security as Security.Tls).serverName shouldBe "a.example"
    }

    @Test
    fun `defaults to tls with sni falling back to host`() {
        val result = parseTrojanLink("trojan://pw@host.example:443", 0) as LinkResult.Ok
        val out = result.profile.outbound as TrojanOutbound
        val security = out.stream.security as Security.Tls

        security.serverName shouldBe "host.example"
        security.fingerprint shouldBe "chrome"
        security.allowInsecure shouldBe false
    }

    @Test
    fun `blank optional values use safe defaults`() {
        val result =
            parseTrojanLink(
                "trojan://pw@host.example:443?type=&sni=&fp=&allowInsecure=0",
                0,
            ) as LinkResult.Ok
        val out = result.profile.outbound as TrojanOutbound
        val security = out.stream.security as Security.Tls

        out.stream.network shouldBe "tcp"
        security.serverName shouldBe "host.example"
        security.fingerprint shouldBe "chrome"
        security.allowInsecure shouldBe false
    }

    @Test
    fun `websocket path and host survive parsing`() {
        val result =
            parseTrojanLink(
                "trojan://pw@host.example:443?type=ws&path=%2Frpc&host=cdn.example#WS",
                0,
            ) as LinkResult.Ok
        val transport = (result.profile.outbound as TrojanOutbound).stream.transport

        transport shouldBe
            TransportOptions.WebSocket(
                path = "/rpc",
                headers = mapOf("Host" to "cdn.example"),
            )
    }

    @Test
    fun `websocket preserves explicit empty path and host`() {
        val result =
            parseTrojanLink(
                "trojan://pw@host.example:443?type=ws&path=&host=",
                0,
            ) as LinkResult.Ok
        val transport = (result.profile.outbound as TrojanOutbound).stream.transport

        transport shouldBe TransportOptions.WebSocket(path = "", headers = mapOf("Host" to ""))
    }

    @Test
    fun `websocket distinguishes absent fields from explicit empty fields`() {
        val result = parseTrojanLink("trojan://pw@host.example:443?type=ws", 0) as LinkResult.Ok
        val transport = (result.profile.outbound as TrojanOutbound).stream.transport

        transport shouldBe TransportOptions.WebSocket(path = "/", headers = emptyMap())
    }

    @Test
    fun `grpc service name survives parsing including explicit empty`() {
        val named = parseTrojanLink("trojan://pw@host.example:443?type=grpc&serviceName=ray", 0) as LinkResult.Ok
        val empty = parseTrojanLink("trojan://pw@host.example:443?type=grpc&serviceName=", 0) as LinkResult.Ok

        (named.profile.outbound as TrojanOutbound).stream.transport shouldBe TransportOptions.Grpc("ray")
        (empty.profile.outbound as TrojanOutbound).stream.transport shouldBe TransportOptions.Grpc("")
    }

    @Test
    fun `percent-decoded passwords survive`() {
        val result = parseTrojanLink("trojan://p%40ss%20word@host.example:443", 0) as LinkResult.Ok
        (result.profile.outbound as TrojanOutbound).password shouldBe "p@ss word"
    }

    @Test
    fun `rejects an empty password`() {
        val result = parseTrojanLink("trojan://@host.example:443", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `rejects a bad port`() {
        val result = parseTrojanLink("trojan://pw@host.example:0", 0) as LinkResult.Bad
        result.failure.reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `malformed input never throws and failures do not echo credentials`() {
        listOf("", "x", "trojan://", "trojan://@", "trojan://secret@host:", "trojan://[\u0000")
            .forEach { raw ->
                val result = parseTrojanLink(raw, 3) as LinkResult.Bad
                result.failure.detail shouldBe FailureDetail.Malformed(DetailField.Uri)
            }
    }
}
