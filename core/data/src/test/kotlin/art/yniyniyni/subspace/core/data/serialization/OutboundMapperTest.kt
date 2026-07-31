// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class OutboundMapperTest {
    private val stream =
        StreamSettings(
            network = "ws",
            security = Security.Reality("sni.example", "pk", "0123abcd", "chrome", ""),
            transport = TransportOptions.WebSocket("/ray", mapOf("Host" to "cdn.example")),
        )

    private val vless =
        VlessOutbound(
            address = "198.51.100.1",
            port = 443,
            uuid = "8f2c4a1e-0000-4000-8000-000000000001",
            flow = "xtls-rprx-vision",
            stream = stream,
        )

    @Test
    fun `an outbound round-trips through json`() {
        vless.toJson().toOutbound() shouldBe vless
    }

    @Test
    fun `serialization is deterministic`() {
        vless.toJson() shouldBe vless.toJson()
    }

    @Test
    fun `unreadable json decodes to null rather than throwing`() {
        "not json at all".toOutbound() shouldBe null
    }

    @Test
    fun `identity distinguishes servers differing only by sni`() {
        val otherSecurity = Security.Reality("other.example", "pk", "0123abcd", "chrome", "")
        val other = vless.copy(stream = vless.stream.copy(security = otherSecurity))

        identityHashOf(vless) shouldNotBe identityHashOf(other)
    }

    @Test
    fun `identity distinguishes servers differing only by flow`() {
        identityHashOf(vless) shouldNotBe identityHashOf(vless.copy(flow = null))
    }

    @Test
    fun `raw json identity is the hash of the exact bytes`() {
        identityHashOfRaw("""{"a":1}""") shouldNotBe identityHashOfRaw("""{"a": 1}""")
    }

    // Fix round 1, Important finding 1: a Map's serialized byte order follows
    // iteration order, not declaration order, so identityHashOf must not
    // depend on which order a caller happened to insert headers in — only on
    // what the headers actually are.
    @Test
    fun `identity is independent of headers map insertion order`() {
        val ascending = vless.copy(stream = withHeaders(headersInOrder("Host", "X-Foo")))
        val descending = vless.copy(stream = withHeaders(headersInOrder("X-Foo", "Host")))

        identityHashOf(ascending) shouldBe identityHashOf(descending)
    }

    private fun withHeaders(headers: Map<String, String>): StreamSettings =
        stream.copy(transport = TransportOptions.WebSocket("/ray", headers))

    private fun headersInOrder(vararg keysInOrder: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        keysInOrder.forEach { key -> headers[key] = "value-$key" }
        return headers
    }

    // Fix round 1, Important finding 2: the original six tests exercise only
    // VlessOutbound + Security.Reality + TransportOptions.WebSocket. §10.1 —
    // nothing here is validated by compilation, and OutboundMapper's
    // hand-written `when` in each direction can drop or mis-map a field for
    // any other protocol without a compile error. This table covers every
    // Outbound subtype and every Security/TransportOptions variant.
    @Test
    fun `every outbound variant round-trips through json`() {
        outboundSamples.forEach { (name, outbound) ->
            withClue(name) {
                outbound.toJson().toOutbound() shouldBe outbound
            }
        }
    }

    // A relationship between two samples, not a self-round-trip, so it does
    // not belong in the table above: an empty headers map is a WebSocket
    // transport with nothing to say, not the absence of a transport, and the
    // two must not decode to the same thing.
    @Test
    fun `an empty websocket headers map is distinct from no transport at all`() {
        val withEmptyHeaders = trojanWith(emptyWebSocketStream)
        val withNoTransport = trojanWith(plainStream)

        withEmptyHeaders.toJson().toOutbound() shouldBe withEmptyHeaders
        withNoTransport.toJson().toOutbound() shouldBe withNoTransport
        withEmptyHeaders shouldNotBe withNoTransport
    }

    private fun trojanWith(stream: StreamSettings) =
        TrojanOutbound(address = "198.51.100.2", port = 443, password = "trojan-pw", stream = stream)

    companion object {
        private val realityStream =
            StreamSettings(
                network = "ws",
                security = Security.Reality("sni.example", "pk", "0123abcd", "chrome", ""),
                transport = TransportOptions.WebSocket("/ray", mapOf("Host" to "cdn.example")),
            )

        private val tlsGrpcStream =
            StreamSettings(
                network = "grpc",
                security = Security.Tls(serverName = "tls.example", fingerprint = "chrome", allowInsecure = false),
                transport = TransportOptions.Grpc(serviceName = "my.Service"),
            )

        private val plainStream =
            StreamSettings(network = "tcp", security = Security.None, transport = TransportOptions.None)

        private val emptyWebSocketStream =
            plainStream.copy(network = "ws", transport = TransportOptions.WebSocket(path = "/", headers = emptyMap()))

        private val outboundSamples: List<Pair<String, Outbound>> =
            listOf(
                "vless + reality + websocket" to
                    VlessOutbound(
                        address = "198.51.100.1",
                        port = 443,
                        uuid = "8f2c4a1e-0000-4000-8000-000000000001",
                        flow = "xtls-rprx-vision",
                        stream = realityStream,
                    ),
                "vless with null flow + tcp + no security" to
                    VlessOutbound(
                        address = "198.51.100.1",
                        port = 443,
                        uuid = "8f2c4a1e-0000-4000-8000-000000000001",
                        flow = null,
                        stream = plainStream,
                    ),
                "vmess + tls + grpc" to
                    VmessOutbound(
                        address = "198.51.100.1",
                        port = 8443,
                        uuid = "8f2c4a1e-0000-4000-8000-000000000002",
                        alterId = 0,
                        security = "auto",
                        stream = tlsGrpcStream,
                    ),
                "vmess + no security + tcp" to
                    VmessOutbound(
                        address = "198.51.100.1",
                        port = 8443,
                        uuid = "8f2c4a1e-0000-4000-8000-000000000002",
                        alterId = 1,
                        security = "none",
                        stream = plainStream,
                    ),
                "trojan + reality" to
                    TrojanOutbound(
                        address = "198.51.100.1",
                        port = 443,
                        password = "trojan-pw",
                        stream = realityStream,
                    ),
                "trojan + tcp + no security" to
                    TrojanOutbound(
                        address = "198.51.100.1",
                        port = 443,
                        password = "trojan-pw",
                        stream = plainStream,
                    ),
                "shadowsocks" to
                    ShadowsocksOutbound(
                        address = "198.51.100.1",
                        port = 8388,
                        method = "aes-256-gcm",
                        password = "ss-pw",
                    ),
                "socks with credentials" to
                    SocksOutbound(
                        address = "198.51.100.1",
                        port = 1080,
                        username = "socks-user",
                        password = "socks-pw",
                    ),
                "socks without credentials" to
                    SocksOutbound(address = "198.51.100.1", port = 1080, username = null, password = null),
            )
    }
}
