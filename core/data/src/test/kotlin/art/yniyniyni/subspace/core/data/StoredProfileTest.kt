// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Fix round 2, Important finding 3: `:core:xray`'s `XrayConfigGenerator` never emits
 * `wsSettings`/`grpcSettings`/`xhttpSettings` and never reads `StreamSettings.transport` —
 * only `network == "tcp"` profiles actually produce a working config (see that generator's
 * `appendStreamSettings`). [StoredProfile.connectable] used to check only the protocol, so a
 * `ws`/`grpc`/`xhttp` VLESS row claimed to be connectable when it was not.
 */
class StoredProfileTest {
    private fun profile(
        protocol: String = "vless",
        network: String = "tcp",
        outboundOverride: art.yniyniyni.subspace.core.model.Outbound? = null,
    ) = StoredProfile(
        id = 1,
        groupId = 1,
        kind = ProfileKind.TYPED,
        name = "Test",
        protocol = protocol,
        address = "198.51.100.1",
        port = 443,
        transport = network,
        outbound =
        outboundOverride ?: when (protocol) {
            "vless" ->
                VlessOutbound(
                    address = "198.51.100.1",
                    port = 443,
                    uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                    flow = null,
                    stream = StreamSettings(network = network, security = Security.None),
                )

            "trojan" ->
                TrojanOutbound(
                    address = "198.51.100.1",
                    port = 443,
                    password = "unused",
                    stream = StreamSettings(network = network, security = Security.None),
                )

            else -> error("unhandled protocol $protocol in test fixture")
        },
        rawJson = null,
        lastConnectedAt = null,
        lastError = null,
    )

    @Test
    fun `a tcp vless profile is connectable`() {
        profile(protocol = "vless", network = "tcp").connectable shouldBe true
    }

    @Test
    fun `a ws vless profile is not connectable`() {
        profile(protocol = "vless", network = "ws").connectable shouldBe false
    }

    @Test
    fun `a grpc vless profile is not connectable`() {
        profile(protocol = "vless", network = "grpc").connectable shouldBe false
    }

    @Test
    fun `a tcp non-vless profile is not connectable`() {
        profile(protocol = "trojan", network = "tcp").connectable shouldBe false
    }

    @Test
    fun `a row whose outbound failed to decode is not connectable`() {
        profile(protocol = "vless", outboundOverride = null).copy(outbound = null).connectable shouldBe false
    }
}
