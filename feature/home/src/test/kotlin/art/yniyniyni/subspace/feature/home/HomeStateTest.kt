// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Fix round 2, Important finding 4: [HomeState.canConnect] used to check only that
 * [HomeState.activeProfile] had a decoded outbound, never
 * [art.yniyniyni.subspace.core.data.StoredProfile.connectable] — which
 * [ServersState][art.yniyniyni.subspace.feature.profiles.list.ServersState] already surfaces.
 * A stored `ws`/`grpc` VLESS profile set active therefore passed `canConnect`, prompted for
 * VPN permission, started the foreground service, and failed there instead of being refused
 * up front.
 */
class HomeStateTest {
    private fun profile(network: String) =
        StoredProfile(
            id = 1,
            groupId = 1,
            kind = ProfileKind.TYPED,
            name = "Test",
            protocol = "vless",
            address = "198.51.100.1",
            port = 443,
            transport = network,
            outbound =
            VlessOutbound(
                address = "198.51.100.1",
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = network, security = Security.None),
            ),
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

    @Test
    fun `canConnect is true for a connectable active profile while disconnected`() {
        val state = HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("tcp"))
        state.canConnect shouldBe true
    }

    @Test
    fun `canConnect is false for a ws active profile even while disconnected`() {
        val state = HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("ws"))
        state.canConnect shouldBe false
    }

    @Test
    fun `activeProfileUnsupported is false when nothing is selected`() {
        HomeState(activeProfile = null).activeProfileUnsupported shouldBe false
    }

    @Test
    fun `activeProfileUnsupported is false for a connectable profile`() {
        HomeState(activeProfile = profile("tcp")).activeProfileUnsupported shouldBe false
    }

    @Test
    fun `activeProfileUnsupported is true for a decoded but unconnectable profile`() {
        HomeState(activeProfile = profile("ws")).activeProfileUnsupported shouldBe true
    }
}
