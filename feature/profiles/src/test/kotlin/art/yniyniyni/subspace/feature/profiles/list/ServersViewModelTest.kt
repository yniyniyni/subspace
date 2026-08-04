// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers what Task 18 builds: the Servers screen's search, protocol filter and
 * sort over the profiles [art.yniyniyni.subspace.core.data.ProfileRepository]
 * already stores, plus the two honesty checks §6 requires — a `RAW_JSON` row
 * says it is running in *compatibility mode*, never "raw" (passthrough is not
 * implemented), and a row whose protocol `:core:xray` cannot emit is flagged
 * `connectable = false` rather than silently letting the user pick a server
 * that will fail with `ProtocolNotSupported` (the gap Task 17 left open).
 *
 * Backtick test names keep the spaces the brief wrote them with, same as
 * [art.yniyniyni.subspace.feature.home.HomeViewModelTest]: this file runs as a
 * plain JVM unit test (`:feature:profiles:testDebugUnitTest`), never through
 * D8/dexing, so the DEX 040 synthetic-class-name restriction that forces
 * camelCase in this repo's *instrumented* tests does not apply here.
 *
 * `viewModelScope` needs a Main dispatcher outside Android, hence
 * [UnconfinedTestDispatcher]. [FakeProfileSource] mirrors the SQL-level
 * search/filter [art.yniyniyni.subspace.core.data.ProfileRepository.observeGroups]
 * now performs (Task 18): matching name/address/transport case-insensitively
 * and protocol by exact value, entirely in memory since there is no real
 * database here — the fixtures below stand in for what a real query would
 * return.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelTest {
    // Every field below is exercised by at least one test (search matches
    // name/address/transport, sort reads lastConnectedAt, kind/protocol drive
    // compatibilityMode/connectable) — this is StoredProfile's real shape,
    // not a param list that shrinks by inventing a builder.
    @Suppress("LongParameterList")
    private fun storedProfile(
        id: Long,
        name: String,
        protocol: String,
        address: String,
        transport: String,
        kind: ProfileKind = ProfileKind.TYPED,
        lastConnectedAt: Long? = null,
    ): StoredProfile =
        StoredProfile(
            id = id,
            groupId = 1L,
            kind = kind,
            name = name,
            protocol = protocol,
            address = address,
            port = 443,
            transport = transport,
            outbound = null,
            rawJson = null,
            lastConnectedAt = lastConnectedAt,
            lastError = null,
        )

    // Frankfurt: VLESS, matches the search query "cdn.example" via its address.
    private val frankfurt =
        storedProfile(
            id = 1L,
            name = "Frankfurt",
            protocol = "vless",
            address = "cdn.example.com",
            transport = "ws · tls · 443",
            lastConnectedAt = 1_000L,
        )

    // Tokyo: Trojan — :core:xray only emits VLESS, so this is the
    // not-connectable fixture. Never connected, so it anchors the LastUsed tie.
    private val tokyo =
        storedProfile(
            id = 2L,
            name = "Tokyo",
            protocol = "trojan",
            address = "jp.example.net",
            transport = "tcp · tls · 443",
        )

    // Berlin: a hand-pasted config.json, so kind = RAW_JSON — the compatibility
    // mode fixture. Never connected, ties with Tokyo for LastUsed.
    private val berlin =
        storedProfile(
            id = 3L,
            name = "Berlin",
            protocol = "vless",
            address = "berlin.example.org",
            transport = "tcp · reality · 443",
            kind = ProfileKind.RAW_JSON,
        )

    // Amsterdam: VLESS, connected more recently than never but less recently
    // than Frankfurt — the middle rung of the LastUsed ordering.
    private val amsterdam =
        storedProfile(
            id = 4L,
            name = "Amsterdam",
            protocol = "vless",
            address = "ams.example.net",
            transport = "tcp · tls · 443",
            lastConnectedAt = 500L,
        )

    private val group =
        ProfileGroup(id = 1L, name = "Local configs", profiles = listOf(frankfurt, tokyo, berlin, amsterdam))

    /**
     * Mirrors [art.yniyniyni.subspace.core.data.ProfileRepository.observeGroups]'s
     * SQL-level filtering closely enough to exercise [ServersViewModel] without a
     * real database — the same role [art.yniyniyni.subspace.feature.home.HomeViewModelTest]'s
     * `FakeActiveProfileSource` plays for `:feature:home`.
     */
    private class FakeProfileSource(
        private val allGroups: List<ProfileGroup>,
    ) : ProfileSource {
        private val _activeProfileId = MutableStateFlow<Long?>(null)
        override val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()

        var lastActiveSet: Long? = null
            private set
        var lastRenamedGroup: Pair<Long, String>? = null
            private set
        var lastDeletedGroup: Long? = null
            private set

        override fun observeGroups(
            query: String,
            protocol: String?,
        ): Flow<List<ProfileGroup>> =
            MutableStateFlow(
                allGroups.map { g ->
                    g.copy(profiles = g.profiles.filter { matchesQuery(it, query) && matchesProtocol(it, protocol) })
                },
            )

        private fun matchesQuery(
            profile: StoredProfile,
            query: String,
        ): Boolean =
            query.isBlank() ||
                profile.name.contains(query, ignoreCase = true) ||
                profile.address.contains(query, ignoreCase = true) ||
                profile.transport.contains(query, ignoreCase = true)

        private fun matchesProtocol(
            profile: StoredProfile,
            protocol: String?,
        ): Boolean = protocol == null || profile.protocol == protocol

        override suspend fun setActiveProfile(id: Long?) {
            lastActiveSet = id
            _activeProfileId.value = id
        }

        override suspend fun renameGroup(
            id: Long,
            name: String,
        ) {
            lastRenamedGroup = id to name
        }

        override suspend fun deleteGroup(id: Long) {
            lastDeletedGroup = id
        }

        // Not exercised by this ViewModel — ServersViewModel never imports or
        // reads a single profile back. Real behaviour for these three is
        // covered by ImportViewModelTest's own fake, which Task 19 added.
        override suspend fun defaultGroupId(): Long = 1L

        override suspend fun import(
            profiles: List<Profile>,
            groupId: Long,
            rawJson: String?,
        ) = Unit

        override suspend fun profile(id: Long): StoredProfile? = null
    }

    private lateinit var source: FakeProfileSource
    private lateinit var viewModel: ServersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeProfileSource(listOf(group))
        viewModel = ServersViewModel(source)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search matches name, address and transport`() =
        runTest {
            viewModel.onQueryChanged("cdn.example")
            advanceUntilIdle()

            viewModel.state.value.groups.flatMap { it.profiles }.map { it.name } shouldBe listOf("Frankfurt")
        }

    @Test
    fun `protocol filters are derived from what is stored, not hardcoded`() =
        runTest {
            // Only VLESS and Trojan rows exist, so no Hysteria2 chip should appear.
            viewModel.state.value.availableProtocols shouldBe listOf("All", "VLESS", "Trojan")
        }

    @Test
    fun `sort cycles alphabetical, as-listed and last-used only`() =
        runTest {
            SortOrder.entries.map { it.name } shouldBe listOf("Alphabetical", "AsListed", "LastUsed")
        }

    @Test
    fun `a raw json profile is flagged as running in compatibility mode`() =
        runTest {
            val row = viewModel.state.value.groups.first().profiles.single { it.kind == ProfileKind.RAW_JSON }
            row.compatibilityMode shouldBe true
        }

    @Test
    fun `a profile whose protocol the core cannot emit is not connectable`() =
        runTest {
            val trojan = viewModel.state.value.groups.flatMap { it.profiles }.single { it.protocol == "trojan" }
            trojan.connectable shouldBe false
        }

    // Beyond the brief's five: proves the filter/sort/selection plumbing this
    // screen exists for actually works end to end, not just that the enum and
    // the two per-row flags are shaped correctly.

    @Test
    fun `selecting a profile sets it active through the source, not locally`() =
        runTest {
            viewModel.onProfileSelected(berlin.id)
            advanceUntilIdle()

            source.lastActiveSet shouldBe berlin.id
            viewModel.state.value.groups.flatMap { it.profiles }.single { it.id == berlin.id }.isActive shouldBe true
        }

    @Test
    fun `the protocol filter narrows every group's rows, not just hides them client-side`() =
        runTest {
            viewModel.onProtocolFilterChanged("Trojan")
            advanceUntilIdle()

            viewModel.state.value.groups.flatMap { it.profiles }.map { it.name } shouldBe listOf("Tokyo")
        }

    @Test
    fun `alphabetical sort orders rows by name`() =
        runTest {
            viewModel.onSortChanged(SortOrder.Alphabetical)
            advanceUntilIdle()

            viewModel.state.value.groups.flatMap { it.profiles }.map { it.name } shouldBe
                listOf("Amsterdam", "Berlin", "Frankfurt", "Tokyo")
        }

    @Test
    fun `last-used sort puts the most recently connected first and never-connected last`() =
        runTest {
            viewModel.onSortChanged(SortOrder.LastUsed)
            advanceUntilIdle()

            viewModel.state.value.groups.flatMap { it.profiles }.map { it.name } shouldBe
                listOf("Frankfurt", "Amsterdam", "Tokyo", "Berlin")
        }

    // Fix round 1, finding 4: totalProfileCount backs the delete-confirmation
    // dialog's cascading-delete warning (the brief's "states the count"), so
    // an active search/filter must never make it report the filtered size —
    // that would understate the blast radius of an irreversible delete.
    @Test
    fun `a group's total profile count stays the real size while a filter narrows what's shown`() =
        runTest {
            viewModel.onProtocolFilterChanged("Trojan")
            advanceUntilIdle()

            val visibleGroup = viewModel.state.value.groups.single()
            visibleGroup.profiles.map { it.name } shouldBe listOf("Tokyo")
            visibleGroup.totalProfileCount shouldBe group.profiles.size
        }
}
