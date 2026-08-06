// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.failure
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Fix round 2, Important finding 4: [HomeState.canConnect] used to check only that
 * [HomeState.activeProfile] had a decoded outbound, never
 * [art.yniyniyni.subspace.core.data.StoredProfile.connectable] — which
 * [ServersState][art.yniyniyni.subspace.feature.profiles.list.ServersState] already surfaces.
 * A stored profile whose transport `:core:xray` could not emit therefore passed `canConnect`,
 * prompted for VPN permission, started the foreground service, and failed there instead of
 * being refused up front.
 *
 * That gate delegates to `connectable` and is still right; what `connectable` *means* has
 * since changed. `ws`/`grpc`/`xhttp` are now emitted by `XrayConfigGenerator`, so they are
 * connectable, and the "unsupported" fixture here is a transport that genuinely has no
 * emission (`kcp`). This file asserts the delegation, not a hardcoded list of transports —
 * `StoredProfileTest` in `:core:data` owns which networks qualify.
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
    fun `canConnect is true for an xhttp active profile while disconnected`() {
        // The regression, at the Home gate: this profile could connect, and the
        // control refused the tap and explained that the build did not support it.
        val state = HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("xhttp"))
        state.canConnect shouldBe true
    }

    @Test
    fun `canConnect is false for a transport the generator cannot emit`() {
        val state = HomeState(connection = ConnectionState.Disconnected, activeProfile = profile("kcp"))
        state.canConnect shouldBe false
    }

    // ── Retry from a failed connection (PR #4 review, P1 finding B) ─────────
    //
    // ConnectionState.Failed renders as ConnectVisualState.Disconnected and
    // TunnelService.startTunnel explicitly accepts a connect from Failed, but
    // canConnect accepted only literal Disconnected. After any validation or
    // startup failure the control therefore looked retryable and ignored taps
    // until an unrelated lifecycle event reset the state.

    private val failed = failure(FailureReason.CoreStartFailed, "redacted")

    @Test
    fun `canConnect is true for a connectable profile after a failure`() {
        HomeState(connection = failed, activeProfile = profile("tcp")).canConnect shouldBe true
    }

    @Test
    fun `canConnect is false after a failure when the profile is unsupported`() {
        // A retry that cannot succeed must stay refused: the failure state does
        // not widen which profiles are connectable, only when a connect is allowed.
        HomeState(connection = failed, activeProfile = profile("kcp")).canConnect shouldBe false
    }

    @Test
    fun `canConnect is false after a failure when the profile is corrupt`() {
        val corrupt = profile("tcp").copy(outbound = null)
        HomeState(connection = failed, activeProfile = corrupt).canConnect shouldBe false
    }

    @Test
    fun `canConnect is false after a failure when nothing is selected`() {
        HomeState(connection = failed, activeProfile = null).canConnect shouldBe false
    }

    @Test
    fun `canConnect stays false in every non-terminal state`() {
        // The other half of the finding: widening to Failed must not accidentally
        // admit a second attempt while one is in flight or a session is live.
        val busy =
            listOf<ConnectionState>(
                ConnectionState.Connecting(StartupStage.AllocatingPort),
                ConnectionState.Connecting(StartupStage.StartingCore),
                ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808),
                ConnectionState.Disconnecting,
            )

        busy.forEach { connection ->
            withClue(connection.toString()) {
                HomeState(connection = connection, activeProfile = profile("tcp")).canConnect shouldBe false
            }
        }
    }

    @Test
    fun `the failure reason and detail survive until a retry begins`() {
        // The state is mirrored verbatim (§5.5), so making it retryable must not
        // clear what it says — HomeScreen renders reason and detail from here.
        val state = HomeState(connection = failed, activeProfile = profile("tcp"))

        state.canConnect shouldBe true
        val connection = state.connection as ConnectionState.Failed
        connection.reason shouldBe FailureReason.CoreStartFailed
        connection.detail shouldBe failed.detail
    }

    @Test
    fun `activeProfileUnsupported is false when nothing is selected`() {
        HomeState(activeProfile = null).activeProfileUnsupported shouldBe false
    }

    @Test
    fun `activeProfileUnsupported is false for a connectable profile`() {
        HomeState(activeProfile = profile("tcp")).activeProfileUnsupported shouldBe false
        HomeState(activeProfile = profile("xhttp")).activeProfileUnsupported shouldBe false
    }

    @Test
    fun `activeProfileUnsupported is true for a decoded but unconnectable profile`() {
        HomeState(activeProfile = profile("kcp")).activeProfileUnsupported shouldBe true
    }
}
