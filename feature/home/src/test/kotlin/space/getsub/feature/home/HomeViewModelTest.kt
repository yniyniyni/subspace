// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

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
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StartupStage
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TrafficSample
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.failure

/**
 * Covers what Task 17 rewrote [HomeViewModel] to do: connect using the profile
 * [space.getsub.core.data.SettingsRepository.activeProfileId] names
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
 * ([space.getsub.core.ui.component.ConnectControlTest] et al.) does
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

    /** Mirrors [space.getsub.core.data.SettingsRepository]'s active-profile slice. */
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

        private val _latencies = MutableStateFlow<Map<Long, LatencyResult>>(emptyMap())
        override val latencies: StateFlow<Map<Long, LatencyResult>> = _latencies.asStateFlow()

        private val _measuring = MutableStateFlow<Set<Long>>(emptySet())
        override val measuring: StateFlow<Set<Long>> = _measuring.asStateFlow()

        private val _traffic = MutableStateFlow<TrafficSample?>(null)
        override val traffic: StateFlow<TrafficSample?> = _traffic.asStateFlow()

        fun emitTraffic(next: TrafficSample?) {
            _traffic.value = next
        }

        /** Off by default here so existing tests keep their "nothing measured yet" baseline. */
        var launchPingEnabled: Boolean = false
        override val pingOnLaunch: Flow<Boolean> get() = MutableStateFlow(launchPingEnabled)

        /** Off by default, mirroring `SettingsRepository.perTagBreakdown`'s own default. */
        var perTagBreakdownEnabled: Boolean = false
        override val perTagBreakdown: Flow<Boolean> get() = MutableStateFlow(perTagBreakdownEnabled)

        /** Settable so a test can drive the failure branch, not only the happy one. */
        var resultToReturn: LatencyResult = LatencyResult.ok(42)

        /** Leaves the measurement in flight, so the "testing" state can be asserted. */
        var autoComplete: Boolean = true

        var measuredIds: List<Long> = emptyList()
            private set

        override suspend fun measure(profileId: Long) {
            measuredIds = measuredIds + profileId
            if (autoComplete) {
                _latencies.value = _latencies.value + (profileId to resultToReturn)
            } else {
                _measuring.value = _measuring.value + profileId
            }
        }
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
    fun `traffic mirrors the tunnel's, verbatim`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)

            val sample =
                TrafficSample(uplinkBytes = 1_024, downlinkBytes = 2_048, uplinkPackets = 4, downlinkPackets = 8)
            tunnel.emitTraffic(sample)

            viewModel.state.value.traffic shouldBe sample
        }

    @Test
    fun `the per-tag breakdown setting mirrors the tunnel's, off by default`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            val profileSource =
                FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)

            viewModel.state.value.perTagBreakdownEnabled shouldBe false
        }

    @Test
    fun `the per-tag breakdown setting is surfaced when on`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            tunnel.perTagBreakdownEnabled = true
            val profileSource =
                FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, profileSource)

            viewModel.state.value.perTagBreakdownEnabled shouldBe true
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

    // ── M4.5: the LATENCY tile ──────────────────────────────────────────────

    /** The active-profile fixture these tests share: one selectable VLESS row. */
    private fun latencyFixture(): Triple<FakeSettings, FakeTunnelConnection, FakeActiveProfileSource> {
        val settings = FakeSettings()
        val tunnel = FakeTunnelConnection()
        val source =
            FakeActiveProfileSource(
                profiles = listOf(storedProfile(id = 1L, name = "Frankfurt")),
                activeProfileId = settings.activeProfileId,
            )
        settings.setActiveProfile(1L)
        return Triple(settings, tunnel, source)
    }

    @Test
    fun `the latency tile is empty until a measurement is taken`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            // Nothing measures on connect and nothing measures periodically, so
            // this is the state until the user asks.
            viewModel.state.value.latency shouldBe null
            tunnel.measuredIds shouldBe emptyList()
        }

    @Test
    fun `testing the active profile fills the tile`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onTestLatency()
            advanceUntilIdle()

            tunnel.measuredIds shouldBe listOf(1L)
            viewModel.state.value.latency shouldBe LatencyResult.ok(42)
        }

    @Test
    fun `a failed measurement is shown as a failure, not as a number`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            tunnel.resultToReturn = LatencyResult.failed(LatencyOutcome.UNREACHABLE)
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onTestLatency()
            advanceUntilIdle()

            viewModel.state.value.latency shouldBe LatencyResult.failed(LatencyOutcome.UNREACHABLE)
        }

    @Test
    fun `testing does nothing when there is no active profile`() =
        runTest {
            val settings = FakeSettings()
            val tunnel = FakeTunnelConnection()
            val source = FakeActiveProfileSource(profiles = emptyList(), activeProfileId = settings.activeProfileId)
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onTestLatency()
            advanceUntilIdle()

            tunnel.measuredIds shouldBe emptyList()
            viewModel.state.value.isMeasuringLatency shouldBe false
        }

    @Test
    fun `showing home measures the active profile when launch testing is on`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            tunnel.launchPingEnabled = true
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onHomeShown()
            advanceUntilIdle()

            // Before this, Home sat at an em-dash until the user visited Servers
            // and came back, because ping-on-launch fired only from that list.
            viewModel.state.value.latency shouldBe LatencyResult.ok(42)
        }

    @Test
    fun `showing home again does not re-measure a profile that already has a reading`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            tunnel.launchPingEnabled = true
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onHomeShown()
            advanceUntilIdle()
            viewModel.onHomeShown()
            advanceUntilIdle()

            // Once per session, with no second claim to keep in step with
            // LatencyCache's — "already has a reading" is the whole condition.
            tunnel.measuredIds shouldBe listOf(1L)
        }

    @Test
    fun `showing home measures nothing when launch testing is off`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            tunnel.launchPingEnabled = false
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onHomeShown()
            advanceUntilIdle()

            tunnel.measuredIds shouldBe emptyList()
        }

    @Test
    fun `the tile reports measuring while a run is in flight`() =
        runTest {
            val (_, tunnel, source) = latencyFixture()
            tunnel.autoComplete = false
            val viewModel = HomeViewModel(tunnel, source)
            advanceUntilIdle()

            viewModel.onTestLatency()
            advanceUntilIdle()

            viewModel.state.value.isMeasuringLatency shouldBe true
            viewModel.state.value.latency shouldBe null
        }
}
