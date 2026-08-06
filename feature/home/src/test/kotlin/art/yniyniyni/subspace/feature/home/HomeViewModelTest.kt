// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.failure
import io.kotest.assertions.withClue
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

    /** A transport `:core:xray` has no emission for, so `StoredProfile.connectable` is false. */
    private val kcpOutbound = defaultOutbound.copy(stream = StreamSettings("kcp", Security.None))

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

    /**
     * An active profile that can be swapped after construction.
     *
     * [FakeActiveProfileSource] resolves against a fixed list, so it cannot express "the row
     * changed while the consent dialog was open" — the shape the stale-callback tests need.
     */
    private class MutableActiveProfileSource(initial: StoredProfile?) : ActiveProfileSource {
        private val _activeProfile = MutableStateFlow(initial)
        override val activeProfile: Flow<StoredProfile?> = _activeProfile.asStateFlow()
        override val hasAnyProfile: Flow<Boolean> = MutableStateFlow(true)

        fun emit(next: StoredProfile?) {
            _activeProfile.value = next
        }
    }

    private data class ConnectAttempt(val profile: Profile, val rowId: Long)

    private class FakeTunnelConnection : TunnelConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()

        var lastConnected: ConnectAttempt? = null
            private set

        /** Counted, not just recorded: "started no connection" and "started two" are both bugs. */
        var connectCount: Int = 0
            private set

        fun emit(next: ConnectionState) {
            _state.value = next
        }

        override fun connect(
            profile: Profile,
            rowId: Long,
        ) {
            lastConnected = ConnectAttempt(profile, rowId)
            connectCount++
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

    // ── onConsentGranted's canConnect precondition (PR #4 review, P1 finding B) ──
    //
    // VPN consent is asynchronous: the system dialog is open while this process
    // keeps running, so the connection state or the selected profile can change
    // before approval returns. onConsentGranted documented a canConnect
    // precondition and never enforced it, so a stale callback could start a
    // second connection or connect to a profile that had since become
    // unsupported or been deleted.

    @Test
    fun `consent granted from a failed state starts exactly one connection`() =
        runTest {
            val settings = FakeSettings()
            val profile = storedProfile(id = 3L, name = "retry")
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(profiles = listOf(profile), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)
            settings.setActiveProfile(profile.id)
            tunnel.emit(failure(FailureReason.CoreStartFailed, "redacted"))

            viewModel.onConsentGranted()
            advanceUntilIdle()

            tunnel.connectCount shouldBe 1
            tunnel.lastConnected?.rowId shouldBe profile.id
        }

    @Test
    fun `consent granted while busy starts no connection`() =
        runTest {
            val busy =
                listOf<ConnectionState>(
                    ConnectionState.Connecting(StartupStage.StartingCore),
                    ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10808),
                    ConnectionState.Disconnecting,
                )

            busy.forEach { connection ->
                val settings = FakeSettings()
                val profile = storedProfile(id = 4L, name = "busy")
                val tunnel = FakeTunnelConnection()
                val profileSource =
                    FakeActiveProfileSource(profiles = listOf(profile), activeProfileId = settings.activeProfileId)
                val viewModel = HomeViewModel(tunnel, profileSource)
                settings.setActiveProfile(profile.id)
                tunnel.emit(connection)

                viewModel.onConsentGranted()
                advanceUntilIdle()

                withClue(connection.toString()) { tunnel.connectCount shouldBe 0 }
            }
        }

    @Test
    fun `a stale consent callback does not connect an unsupported profile`() =
        runTest {
            // The profile was connectable when consent was requested and is not by
            // the time it returns — the row was edited to a transport this build
            // cannot emit. Connecting anyway would start the foreground service to
            // fail immediately.
            val settings = FakeSettings()
            val supported = storedProfile(id = 5L, name = "was-fine")
            val unsupported = supported.copy(outbound = kcpOutbound)
            val tunnel = FakeTunnelConnection()
            val profileSource = MutableActiveProfileSource(supported)
            val viewModel = HomeViewModel(tunnel, profileSource)
            viewModel.state.value.canConnect shouldBe true

            profileSource.emit(unsupported)
            viewModel.onConsentGranted()
            advanceUntilIdle()

            tunnel.connectCount shouldBe 0
        }

    @Test
    fun `a stale consent callback does not connect a profile that disappeared`() =
        runTest {
            val settings = FakeSettings()
            val profile = storedProfile(id = 6L, name = "deleted")
            val tunnel = FakeTunnelConnection()
            val profileSource = MutableActiveProfileSource(profile)
            val viewModel = HomeViewModel(tunnel, profileSource)
            viewModel.state.value.canConnect shouldBe true

            profileSource.emit(null)
            viewModel.onConsentGranted()
            advanceUntilIdle()

            tunnel.connectCount shouldBe 0
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
