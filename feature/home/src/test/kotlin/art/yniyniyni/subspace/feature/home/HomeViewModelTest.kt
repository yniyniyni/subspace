// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers what Task 17 rewrote [HomeViewModel] to do: connect using the profile
 * [art.yniyniyni.subspace.core.data.SettingsRepository.activeProfileId] names
 * — never `ProfileRepository`'s first row, the retired M1 shortcut — and mirror
 * [ConnectionState] from [TunnelConnection] verbatim rather than inferring it
 * locally (§5.5).
 *
 * `viewModelScope` needs a Main dispatcher to run at all outside Android, hence
 * [UnconfinedTestDispatcher]. Every source [HomeViewModel] combines
 * ([TunnelConnection.state], [ActiveProfileSource.activeProfile],
 * [ActiveProfileSource.hasAnyProfile]) is backed by a [MutableStateFlow] with a
 * value already available at construction, so — unlike the M1 predecessor of
 * this file, whose `parseInput` genuinely hopped to `Dispatchers.Default` and
 * needed `state.first { predicate }` to await that hop — the combined
 * [HomeState] here is available synchronously off `state.value` under this
 * dispatcher, with no await needed except after [HomeViewModel.onConsentGranted]
 * itself, which is asserted with `advanceUntilIdle()` per the brief.
 *
 * Backtick test names keep the spaces the brief wrote them with: this file
 * runs as a plain JVM unit test (`:feature:home:testDebugUnitTest`), never
 * through D8/dexing, so the DEX 040 synthetic-class-name restriction that
 * forces camelCase in this module's *instrumented* tests
 * ([art.yniyniyni.subspace.core.ui.component.ConnectControlTest] et al.) does
 * not apply here — this module's own pre-existing JVM tests already used
 * backtick-with-spaces names successfully.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val defaultOutbound =
        VlessOutbound(
            address = "example.com",
            port = 443,
            uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab",
            flow = null,
            stream = StreamSettings(network = "tcp", security = Security.None),
        )

    private fun storedProfile(
        id: Long,
        name: String,
        outbound: Outbound? = defaultOutbound,
    ): StoredProfile =
        StoredProfile(
            id = id,
            groupId = 1L,
            kind = ProfileKind.TYPED,
            name = name,
            protocol = "vless",
            address = "example.com",
            port = 443,
            transport = "tcp",
            outbound = outbound,
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

    /** Mirrors [art.yniyniyni.subspace.core.data.SettingsRepository]'s active-profile slice. */
    private class FakeSettings {
        private val _activeProfileId = MutableStateFlow<Long?>(null)
        val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()

        fun setActiveProfile(id: Long?) {
            _activeProfileId.value = id
        }
    }

    private class FakeActiveProfileSource(
        profiles: List<StoredProfile>,
        activeProfileId: Flow<Long?>,
    ) : ActiveProfileSource {
        override val hasAnyProfile: Flow<Boolean> = MutableStateFlow(profiles.isNotEmpty())
        override val activeProfile: Flow<StoredProfile?> =
            activeProfileId.map { id -> profiles.firstOrNull { it.id == id } }
    }

    private data class ConnectAttempt(val profile: Profile, val rowId: Long)

    private class FakeTunnelConnection : TunnelConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()

        var lastConnected: ConnectAttempt? = null
            private set

        fun emit(next: ConnectionState) {
            _state.value = next
        }

        override fun connect(
            profile: Profile,
            rowId: Long,
        ) {
            lastConnected = ConnectAttempt(profile, rowId)
        }

        override fun disconnect() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connecting uses the active profile, not the first row`() =
        runTest {
            val settings = FakeSettings()
            val firstProfile = storedProfile(id = 1L, name = "first")
            val secondProfile = storedProfile(id = 2L, name = "second")
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(
                    profiles = listOf(firstProfile, secondProfile),
                    activeProfileId = settings.activeProfileId,
                )
            val viewModel = HomeViewModel(tunnel, profileSource)

            settings.setActiveProfile(secondProfile.id)

            viewModel.onConsentGranted()
            advanceUntilIdle()

            tunnel.lastConnected?.rowId shouldBe secondProfile.id
        }

    @Test
    fun `with no profile stored the control is disabled and points at import`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)

            viewModel.state.value.hasAnyProfile shouldBe false
            viewModel.state.value.canConnect shouldBe false
        }

    @Test
    fun `connection state comes from the service, never from a local boolean`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)

            tunnel.emit(ConnectionState.Connected(sinceEpochMillis = 1_000L, socksPort = 10808))

            viewModel.state.value.connection shouldBe ConnectionState.Connected(1_000L, 10808)
        }

    @Test
    fun `the connecting stage is surfaced verbatim`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)

            tunnel.emit(ConnectionState.Connecting(StartupStage.ValidatingConfig))

            viewModel.state.value.connection shouldBe ConnectionState.Connecting(StartupStage.ValidatingConfig)
        }
}
