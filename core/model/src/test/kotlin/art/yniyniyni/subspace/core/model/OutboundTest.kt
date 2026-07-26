// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The sealed hierarchy is the point: a `when` over [Outbound] that misses a
 * protocol must fail to compile, so adding a protocol forces every consumer to
 * be revisited. These tests pin the shared contract.
 */
class OutboundTest {
    @Test
    fun `every outbound exposes address and port`() {
        val stream = StreamSettings(network = "tcp", security = Security.None)
        val all: List<Outbound> =
            listOf(
                VlessOutbound("a.example", 443, "uuid", null, stream),
                VmessOutbound("b.example", 8443, "uuid", 0, "auto", stream),
                TrojanOutbound("c.example", 443, "pw", stream),
                ShadowsocksOutbound("d.example", 8388, "aes-256-gcm", "pw"),
                SocksOutbound("e.example", 1080, null, null),
            )

        all.map { it.port } shouldBe listOf(443, 8443, 443, 8388, 1080)
        all.all { it.address.endsWith(".example") } shouldBe true
    }

    @Test
    fun `profile holds any outbound`() {
        val out = TrojanOutbound("t.example", 443, "pw", StreamSettings("tcp", Security.None))
        val profile = Profile(id = "id", name = "n", outbound = out)
        profile.outbound shouldBe out
    }
}
