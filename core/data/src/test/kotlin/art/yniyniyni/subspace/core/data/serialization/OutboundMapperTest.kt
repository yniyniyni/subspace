// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.VlessOutbound
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
}
