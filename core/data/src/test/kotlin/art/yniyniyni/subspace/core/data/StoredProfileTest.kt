// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Covers [StoredProfile.connectable], which has now been wrong in both directions.
 *
 * It first checked only `protocol == "vless"`, which claimed a `ws`/`grpc`/`xhttp` row was
 * connectable while `XrayConfigGenerator` emitted no transport settings for it. Fix round 2,
 * Important finding 3 narrowed it to `network == "tcp"` — which over-corrected: it demoted
 * profiles that did connect, because omitting a transport's settings object makes Xray use
 * that transport's own defaults rather than fail, so an xhttp server on a default path
 * worked. Reported from the field as working servers labelled "not supported by this build
 * yet".
 *
 * The generator now emits `wsSettings`/`grpcSettings`/`xhttpSettings` from
 * `StreamSettings.transport`, so the gate is the set of networks it can emit rather than a
 * single one. These tests pin both edges: every transport the generator handles is
 * connectable, and a transport it does not handle still is not.
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
    fun `a ws vless profile is connectable`() {
        profile(protocol = "vless", network = "ws").connectable shouldBe true
    }

    @Test
    fun `a grpc vless profile is connectable`() {
        profile(protocol = "vless", network = "grpc").connectable shouldBe true
    }

    @Test
    fun `an xhttp vless profile is connectable`() {
        // The regression this whole file now guards: this row used to render
        // "Not supported by this build yet" for a server that worked.
        profile(protocol = "vless", network = "xhttp").connectable shouldBe true
    }

    @Test
    fun `a splithttp vless profile is connectable`() {
        // Xray-core v26.7.11 treats it as the same transport as xhttp.
        profile(protocol = "vless", network = "splithttp").connectable shouldBe true
    }

    @Test
    fun `every network name is matched case-insensitively`() {
        // TransportProtocol.Build lowercases before matching, so a config written
        // with "XHTTP" or "WS" is valid to the core and must not be demoted here.
        profile(protocol = "vless", network = "XHTTP").connectable shouldBe true
        profile(protocol = "vless", network = "WS").connectable shouldBe true
    }

    @Test
    fun `a raw alias tcp profile is connectable`() {
        // v26.7.11 accepts "raw" as tcp's own alias.
        profile(protocol = "vless", network = "raw").connectable shouldBe true
    }

    @Test
    fun `a transport the generator cannot emit is not connectable`() {
        // kcp, httpupgrade and hysteria are real Xray transports with no emission
        // here. The gate must stay an allow-list of what the generator handles —
        // if it were a deny-list, adding a transport to the model would silently
        // promise a config this build cannot write.
        profile(protocol = "vless", network = "kcp").connectable shouldBe false
        profile(protocol = "vless", network = "httpupgrade").connectable shouldBe false
        profile(protocol = "vless", network = "hysteria").connectable shouldBe false
    }

    @Test
    fun `an unrecognised network is not connectable`() {
        profile(protocol = "vless", network = "quic").connectable shouldBe false
        profile(protocol = "vless", network = "").connectable shouldBe false
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
