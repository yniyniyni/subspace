// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import io.kotest.matchers.nulls.shouldBeNull
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
import space.getsub.core.data.AddedSubscription
import space.getsub.core.data.EffectiveValue
import space.getsub.core.data.ProfileGroup
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.sync.SubscriptionSyncFailure
import space.getsub.core.data.sync.SyncResult
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import space.getsub.core.model.Outbound
import space.getsub.core.model.PingMode
import space.getsub.core.model.Profile
import space.getsub.core.model.StartupStage
import space.getsub.feature.profiles.ProfileSource
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.add.UserMessage

/**
 * Covers what Task 18 builds: the Servers screen's search, protocol filter and
 * sort over the profiles [space.getsub.core.data.ProfileRepository]
 * already stores, plus the two honesty checks §6 requires — a `RAW_JSON` row
 * says it is running in *compatibility mode*, never "raw" (passthrough is not
 * implemented), and a row whose protocol `:core:xray` cannot emit is flagged
 * `connectable = false` rather than silently letting the user pick a server
 * that will fail with `ProtocolNotSupported` (the gap Task 17 left open).
 *
 * Backtick test names keep the spaces the brief wrote them with, same as
 * [space.getsub.feature.home.HomeViewModelTest]: this file runs as a
 * plain JVM unit test (`:feature:profiles:testDebugUnitTest`), never through
 * D8/dexing, so the DEX 040 synthetic-class-name restriction that forces
 * camelCase in this repo's *instrumented* tests does not apply here.
 *
 * `viewModelScope` needs a Main dispatcher outside Android, hence
 * [UnconfinedTestDispatcher]. [FakeProfileSource] mirrors the SQL-level
 * search/filter [space.getsub.core.data.ProfileRepository.observeGroups]
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
        droppedFromSubscriptionAt: Long? = null,
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
            droppedFromSubscriptionAt = droppedFromSubscriptionAt,
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
     * Mirrors [space.getsub.core.data.ProfileRepository.observeGroups]'s
     * SQL-level filtering closely enough to exercise [ServersViewModel] without a
     * real database — the same role [space.getsub.feature.home.HomeViewModelTest]'s
     * `FakeActiveProfileSource` plays for `:feature:home`.
     */
    private class FakeProfileSource(
        private val allGroups: List<ProfileGroup>,
        private val subscriptions: List<StoredSubscription> = emptyList(),
        private val userInfoBySubscriptionId: Map<Long, String?> = emptyMap(),
    ) : ProfileSource {
        private val _activeProfileId = MutableStateFlow<Long?>(null)
        override val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()
        override val globalHwidEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()

        var lastActiveSet: Long? = null
            private set
        var lastRenamedGroup: Pair<Long, String>? = null
            private set
        var lastDeletedGroup: Long? = null
            private set

        // Fix round, Important 1: what onUpdateSubscription's wiring is
        // verified against below.
        var syncSubscriptionCallCount = 0
            private set
        var lastSyncedSubscriptionId: Long? = null
            private set

        /**
         * Settable so a test can drive the failure branch. A fake that only ever succeeds is why
         * the discarded-[SyncResult] bug survived to the device run: nothing here could fail.
         */
        var syncResultToReturn: SyncResult = SyncResult.Synced(0, 0, 0, 0, 0)

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
        ) = 0

        override suspend fun profile(id: Long): StoredProfile? = null

        // Not exercised by this ViewModel — same reasoning as defaultGroupId/
        // import/profile above. EditorViewModelTest (Task 21) owns real
        // coverage of these three.
        override suspend fun rename(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun move(
            id: Long,
            toGroupId: Long,
        ) = true

        override suspend fun update(
            id: Long,
            name: String,
            outbound: Outbound,
        ) = true

        // Not exercised here — this fixture is ServersViewModelTest's own,
        // and nothing on the Servers screen touches subscriptions. See
        // ImportViewModelTest's identical stub for the fuller rationale.
        override suspend fun addSubscription(
            url: String,
            name: String,
        ) = AddedSubscription(0L, created = true)

        override suspend fun syncSubscription(id: Long): SyncResult {
            syncSubscriptionCallCount++
            lastSyncedSubscriptionId = id
            return syncResultToReturn
        }

        override suspend fun deleteSubscription(id: Long) = Unit

        // Task 14: ServersViewModel's init block subscribes to this
        // immediately. Most tests in this file construct FakeProfileSource
        // with no subscriptions at all, so the default keeps every one of
        // them exercising exactly the MANUAL-group (no quota) path; the
        // quota-specific tests below pass a real list.
        override fun observeSubscriptions(): Flow<List<StoredSubscription>> = MutableStateFlow(subscriptions)

        override fun observeUserInfo(id: Long): Flow<String?> = MutableStateFlow(userInfoBySubscriptionId[id])

        // Not exercised — nothing on the Servers screen pins a directive or touches HWID/UA.
        // SubscriptionDetailViewModelTest (Task 15) owns real coverage of these five.
        /**
         * Provider directives by subscription id. Settable so a test can drive
         * the per-group sort path — a fake that always reports "no directive"
         * cannot tell a provider-set order from the screen default.
         */
        val directives: MutableMap<Long, MutableMap<String, String>> = mutableMapOf()

        override fun observeEffective(
            id: Long,
            key: String,
            default: String?,
        ): Flow<EffectiveValue> {
            val provider = directives[id]?.get(key)
            return MutableStateFlow(EffectiveValue(key, provider ?: default, provider, isPinned = false))
        }

        override suspend fun pin(
            id: Long,
            key: String,
            value: String,
        ) = Unit

        override suspend fun unpin(
            id: Long,
            key: String,
        ) = Unit

        override suspend fun setHwidEnabled(
            id: Long,
            enabled: Boolean,
        ) = Unit

        override suspend fun setUserAgentOverride(
            id: Long,
            userAgent: String?,
        ) = Unit
    }

    private lateinit var source: FakeProfileSource
    private lateinit var tester: FakeLatencyTester
    private lateinit var viewModel: ServersViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeProfileSource(listOf(group))
        tester = FakeLatencyTester()
        viewModel = ServersViewModel(source, tester)
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
    fun `sort offers alphabetical, as-listed, last-used and fastest`() =
        runTest {
            // Fastest arrived with M4.5, which supplied the measurement it needs.
            // The set stays pinned so a fifth entry is again a deliberate act —
            // see SortOrder's KDoc and §10.1.
            SortOrder.entries.map { it.name } shouldBe
                listOf("Alphabetical", "AsListed", "LastUsed", "Fastest")
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

    @Test
    fun `a server kept after provider removal is visibly marked`() =
        runTest {
            val removed = frankfurt.copy(droppedFromSubscriptionAt = 1_000L)
            val source = FakeProfileSource(listOf(group.copy(profiles = listOf(removed))))
            val viewModel = ServersViewModel(source, tester)
            advanceUntilIdle()

            viewModel.state.value.groups.single().profiles.single().droppedFromSubscriptionAt shouldBe 1_000L
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

    // Task 14: quota data. A MANUAL group (every test above this point) never
    // appears in FakeProfileSource.observeSubscriptions(), so its absence
    // from the quota map — not a separate "is this SUBSCRIPTION" flag — is
    // what already keeps quotaUsedBytes/quotaTotalBytes null there.

    @Test
    fun `a group with no matching subscription renders no quota`() =
        runTest {
            viewModel.state.value.groups.single().quotaUsedBytes.shouldBeNull()
            viewModel.state.value.groups.single().quotaTotalBytes.shouldBeNull()
        }

    // Fix round, Important 1/2: subscriptionId and lastFetchedAtEpochMillis
    // must reach ServersGroup for a SUBSCRIPTION group, and stay null for a
    // MANUAL one — the same "presence in the map is the signal" property the
    // quota fields above already have.

    @Test
    fun `a manual group carries no subscriptionId or last-fetched time`() =
        runTest {
            val visibleGroup = viewModel.state.value.groups.single()
            visibleGroup.subscriptionId.shouldBeNull()
            visibleGroup.lastFetchedAtEpochMillis.shouldBeNull()
        }

    @Test
    fun `a subscription group carries its subscriptionId and last-fetched time`() =
        runTest {
            val subscription =
                StoredSubscription(
                    id = 9L,
                    groupId = group.id,
                    url = "https://example.com/sub",
                    userAgentOverride = null,
                    hwidEnabled = true,
                    lastFetchedAt = 1_700_000_000_000L,
                    lastFetchStatus = null,
                    lastFetchDetail = null,
                )
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            val subscribedViewModel = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            val visibleGroup = subscribedViewModel.state.value.groups.single()
            visibleGroup.subscriptionId shouldBe 9L
            visibleGroup.lastFetchedAtEpochMillis shouldBe 1_700_000_000_000L
        }

    @Test
    fun `updating a subscription group runs one sync of its own subscription id`() =
        runTest {
            val subscription =
                StoredSubscription(
                    id = 9L,
                    groupId = group.id,
                    url = "https://example.com/sub",
                    userAgentOverride = null,
                    hwidEnabled = true,
                    lastFetchedAt = null,
                    lastFetchStatus = null,
                    lastFetchDetail = null,
                )
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            val subscribedViewModel = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            subscribedViewModel.onUpdateSubscription(9L)
            advanceUntilIdle()

            subscribedSource.syncSubscriptionCallCount shouldBe 1
            subscribedSource.lastSyncedSubscriptionId shouldBe 9L
        }

    @Test
    fun `a failed update surfaces its reason instead of failing silently`() =
        runTest {
            // M4's device run: the HWID toggle was off, the panel refused the fetch, and the
            // Servers screen said nothing at all because onUpdateSubscription discarded its
            // SyncResult. HwidRequired specifically, because telling it apart from
            // DeviceLimitReached is the milestone's exit criterion.
            val source = FakeProfileSource(allGroups = emptyList())
            source.syncResultToReturn = SyncResult.Failed(SubscriptionSyncFailure.HwidRequired)
            val viewModel = ServersViewModel(source, tester)
            advanceUntilIdle()

            viewModel.onUpdateSubscription(9L)
            advanceUntilIdle()

            viewModel.state.value.updateResult shouldBe
                UserMessage(R.string.subscription_error_hwid_required)
        }

    @Test
    fun `a successful update reports the rows written`() =
        runTest {
            val source = FakeProfileSource(allGroups = emptyList())
            source.syncResultToReturn = SyncResult.Synced(added = 3, 0, 0, 0, 0)
            val viewModel = ServersViewModel(source, tester)
            advanceUntilIdle()

            viewModel.onUpdateSubscription(9L)
            advanceUntilIdle()

            viewModel.state.value.updateResult shouldBe UserMessage(R.plurals.subscription_added, quantity = 3)
        }

    @Test
    fun `the update result survives the state rebuild the sync itself triggers`() =
        runTest {
            // buildState() does not know about updateResult, and a sync writes to the tables the
            // state flow observes — so a naive assignment erases the message at exactly the moment
            // it becomes relevant.
            val source = FakeProfileSource(allGroups = emptyList())
            source.syncResultToReturn = SyncResult.Failed(SubscriptionSyncFailure.HwidRequired)
            val viewModel = ServersViewModel(source, tester)
            advanceUntilIdle()

            viewModel.onUpdateSubscription(9L)
            advanceUntilIdle()
            viewModel.onQueryChanged("anything") // forces the combine to rebuild the whole state
            advanceUntilIdle()

            viewModel.state.value.updateResult shouldBe
                UserMessage(R.string.subscription_error_hwid_required)
        }

    @Test
    fun `dismissing the update result clears it`() =
        runTest {
            val source = FakeProfileSource(allGroups = emptyList())
            source.syncResultToReturn = SyncResult.Failed(SubscriptionSyncFailure.HwidRequired)
            val viewModel = ServersViewModel(source, tester)
            advanceUntilIdle()
            viewModel.onUpdateSubscription(9L)
            advanceUntilIdle()

            viewModel.onDismissUpdateResult()

            viewModel.state.value.updateResult shouldBe null
        }

    @Test
    fun `quota is parsed from the subscription-userinfo directive of the subscription owning this group`() =
        runTest {
            val subscription =
                StoredSubscription(
                    id = 9L,
                    groupId = group.id,
                    url = "https://example.com/sub",
                    userAgentOverride = null,
                    hwidEnabled = true,
                    lastFetchedAt = null,
                    lastFetchStatus = null,
                    lastFetchDetail = null,
                )
            val subscribedSource =
                FakeProfileSource(
                    allGroups = listOf(group),
                    subscriptions = listOf(subscription),
                    userInfoBySubscriptionId = mapOf(9L to "upload=3; download=7; total=100"),
                )
            val subscribedViewModel = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            val visibleGroup = subscribedViewModel.state.value.groups.single()
            visibleGroup.quotaUsedBytes shouldBe 10L
            visibleGroup.quotaTotalBytes shouldBe 100L
        }

    @Test
    fun `a provider that sent neither upload nor download draws no used-bytes figure`() =
        runTest {
            // §A.1's anti-fabrication rule: UserInfo.usedBytes defaults an
            // absent counter to zero, which is correct when only one of the
            // two is missing but would be a fabricated "0 B used" if the
            // provider sent neither — see ServersViewModel.buildState's own
            // comment on this exclusion.
            val subscription =
                StoredSubscription(
                    id = 9L,
                    groupId = group.id,
                    url = "https://example.com/sub",
                    userAgentOverride = null,
                    hwidEnabled = true,
                    lastFetchedAt = null,
                    lastFetchStatus = null,
                    lastFetchDetail = null,
                )
            val subscribedSource =
                FakeProfileSource(
                    allGroups = listOf(group),
                    subscriptions = listOf(subscription),
                    userInfoBySubscriptionId = mapOf(9L to "total=100"),
                )
            val subscribedViewModel = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            val visibleGroup = subscribedViewModel.state.value.groups.single()
            visibleGroup.quotaUsedBytes.shouldBeNull()
            visibleGroup.quotaTotalBytes shouldBe 100L
        }

    // ── M4.5: latency and per-group sort ────────────────────────────────────

    private fun subscriptionFor(id: Long = 9L) =
        StoredSubscription(
            id = id,
            groupId = group.id,
            url = "https://example.com/sub",
            userAgentOverride = null,
            hwidEnabled = true,
            lastFetchedAt = null,
            lastFetchStatus = null,
            lastFetchDetail = null,
        )

    private fun rowsOf(model: ServersViewModel) = model.state.value.groups.flatMap { it.profiles }

    @Test
    fun `an unmeasured row reports no latency rather than a zero`() =
        runTest {
            advanceUntilIdle()

            // Absent, not 0 ms: a substituted number is §10.1's failure mode.
            rowsOf(viewModel).forEach { it.latency.shouldBeNull() }
        }

    @Test
    fun `a measured row carries its latency and stops showing as testing`() =
        runTest {
            viewModel.onTestProfile(1L)
            advanceUntilIdle()

            val row = rowsOf(viewModel).single { it.id == 1L }
            row.latency shouldBe LatencyResult.ok(42)
            row.isTesting shouldBe false
        }

    @Test
    fun `testing a group measures every visible row, not every stored row`() =
        runTest {
            viewModel.onQueryChanged("cdn.example")
            advanceUntilIdle()
            viewModel.onTestGroup(group.id)
            advanceUntilIdle()

            // Only Frankfurt matches the query; measuring the filtered-out rows
            // would spend the user's battery on servers they cannot see.
            tester.testedIds shouldBe listOf(1L)
        }

    @Test
    fun `a failed measurement is recorded as a failure, not dropped`() =
        runTest {
            tester.resultFor = { LatencyResult.failed(LatencyOutcome.UNREACHABLE) }
            viewModel.onTestProfile(2L)
            advanceUntilIdle()

            rowsOf(viewModel).single { it.id == 2L }.latency shouldBe
                LatencyResult.failed(LatencyOutcome.UNREACHABLE)
        }

    @Test
    fun `a row is marked testing while its measurement is in flight`() =
        runTest {
            val pending = FakeLatencyTester(autoComplete = false)
            val model = ServersViewModel(source, pending)
            advanceUntilIdle()

            model.onTestProfile(1L)
            advanceUntilIdle()

            rowsOf(model).single { it.id == 1L }.isTesting shouldBe true
        }

    @Test
    fun `cancelling returns testing rows to idle without inventing a result`() =
        runTest {
            val pending = FakeLatencyTester(autoComplete = false)
            val model = ServersViewModel(source, pending)
            advanceUntilIdle()
            model.onTestProfile(1L)
            advanceUntilIdle()

            model.onCancelTests()
            advanceUntilIdle()

            val row = rowsOf(model).single { it.id == 1L }
            row.isTesting shouldBe false
            // No result: a cancelled measurement produced no number, and even a
            // recorded failure would claim a test that did not happen.
            row.latency.shouldBeNull()
        }

    @Test
    fun `testing an empty group does not start a run`() =
        runTest {
            viewModel.onQueryChanged("matches-nothing")
            advanceUntilIdle()
            viewModel.onTestGroup(group.id)
            advanceUntilIdle()

            tester.testCallCount shouldBe 0
        }

    @Test
    fun `a group with no provider sort follows the screen default`() =
        runTest {
            viewModel.onSortChanged(SortOrder.Alphabetical)
            advanceUntilIdle()

            val visible = viewModel.state.value.groups.single()
            visible.sort shouldBe SortOrder.Alphabetical
            visible.sortFromProvider shouldBe false
        }

    @Test
    fun `a group whose provider set a sort uses it and is marked as provider-set`() =
        runTest {
            val subscription = subscriptionFor()
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            subscribedSource.directives[subscription.id] = mutableMapOf("subscriptions-sort-type" to "alphabet")
            val model = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            val visible = model.state.value.groups.single()
            visible.sort shouldBe SortOrder.Alphabetical
            // §A.1 requires provider-versus-user precedence to be visible.
            visible.sortFromProvider shouldBe true
        }

    @Test
    fun `a user override beats the provider sort and clears the marker`() =
        runTest {
            val subscription = subscriptionFor()
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            subscribedSource.directives[subscription.id] = mutableMapOf("subscriptions-sort-type" to "alphabet")
            val model = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            model.onGroupSortChanged(group.id, SortOrder.Fastest)
            advanceUntilIdle()

            val visible = model.state.value.groups.single()
            visible.sort shouldBe SortOrder.Fastest
            visible.sortFromProvider shouldBe false
        }

    @Test
    fun `an unrecognised provider sort falls back to the screen default unmarked`() =
        runTest {
            val subscription = subscriptionFor()
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            subscribedSource.directives[subscription.id] = mutableMapOf("subscriptions-sort-type" to "nonsense")
            val model = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()
            model.onSortChanged(SortOrder.LastUsed)
            advanceUntilIdle()

            val visible = model.state.value.groups.single()
            visible.sort shouldBe SortOrder.LastUsed
            visible.sortFromProvider shouldBe false
        }

    @Test
    fun `a provider sort of ping orders that group by measured latency`() =
        runTest {
            val subscription = subscriptionFor()
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            subscribedSource.directives[subscription.id] = mutableMapOf("subscriptions-sort-type" to "ping")
            val model = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            // Amsterdam fastest, Frankfurt slower, the rest unmeasured.
            tester.resultFor = { id -> if (id == 4L) LatencyResult.ok(10) else LatencyResult.ok(300) }
            model.onTestProfile(4L)
            advanceUntilIdle()
            tester.resultFor = { LatencyResult.ok(300) }
            model.onTestProfile(1L)
            advanceUntilIdle()

            model.state.value.groups.single().profiles.take(2).map { it.id } shouldBe listOf(4L, 1L)
        }

    @Test
    fun `showing the list measures every group once, then not again`() =
        runTest {
            advanceUntilIdle()

            viewModel.onServersShown()
            advanceUntilIdle()
            val afterFirst = tester.testCallCount

            // Navigating away and back must not re-run it — the device checklist
            // checks exactly this.
            viewModel.onServersShown()
            advanceUntilIdle()

            afterFirst shouldBe 1
            tester.testCallCount shouldBe 1
        }

    @Test
    fun `showing the list measures a manual group with no provider at all`() =
        runTest {
            advanceUntilIdle()

            viewModel.onServersShown()
            advanceUntilIdle()

            // The fixture group is MANUAL: no subscription, so no directive could
            // ever have enabled this. Ours does.
            tester.testedIds shouldBe listOf(1L, 2L, 3L, 4L)
        }

    // ── Review findings: three defects the device run could not reach ───────
    //
    // The subscription used on device held one group, which hid the first two
    // entirely — a single-group launch has nothing to supersede and nothing left
    // marked. These are the regression guards.

    @Test
    fun `the launch run measures every eligible group in one run, not one run per group`() =
        runTest {
            val second = ProfileGroup(id = 2L, name = "Other", profiles = listOf(frankfurt.copy(id = 9L)))
            val multi = FakeProfileSource(listOf(group, second))
            val model = ServersViewModel(multi, tester)
            advanceUntilIdle()

            model.onServersShown()
            advanceUntilIdle()

            // One run. A loop of per-group starts left only the last group
            // measured, because starting a run supersedes any run in flight.
            tester.testCallCount shouldBe 1
            tester.testedIds shouldBe listOf(1L, 2L, 3L, 4L, 9L)
        }

    @Test
    fun `starting a run clears rows a superseded run had left marked`() =
        runTest {
            val pending = FakeLatencyTester(autoComplete = false)
            val model = ServersViewModel(source, pending)
            advanceUntilIdle()

            model.onTestGroup(group.id)
            advanceUntilIdle()
            // Supersedes the group run while its rows are still marked.
            model.onTestProfile(1L)
            advanceUntilIdle()

            // A superseded run never reaches its own onFinished — it is fenced on
            // the run id — so without an explicit clear these rows would show "…"
            // for the rest of the session.
            val stillTesting = rowsOf(model).filter { it.isTesting }.map { it.id }
            stillTesting shouldBe listOf(1L)
        }

    @Test
    fun `a provider's ping-type sets the mode for that group's servers`() =
        runTest {
            val subscription = subscriptionFor()
            val subscribedSource =
                FakeProfileSource(allGroups = listOf(group), subscriptions = listOf(subscription))
            subscribedSource.directives[subscription.id] = mutableMapOf("ping-type" to "tcp")
            val model = ServersViewModel(subscribedSource, tester)
            advanceUntilIdle()

            model.onTestGroup(group.id)
            advanceUntilIdle()

            // §A.1 scopes ping-type to the subscription that sent it. Before this,
            // the directive was stored and never read — the milestone claimed
            // three directive readers and shipped two.
            val expected = listOf(1L, 2L, 3L, 4L).associateWith { PingMode.TCP }
            tester.lastModes shouldBe expected
        }

    @Test
    fun `a group whose provider sent no ping-type follows the global setting`() =
        runTest {
            viewModel.onTestGroup(group.id)
            advanceUntilIdle()

            // Empty means "no per-group override" — the tester falls back to the
            // user's own mode rather than substituting one.
            tester.lastModes shouldBe emptyMap()
        }

    @Test
    fun `a launch run dropped before the service is bound gives its claim back`() =
        runTest {
            tester.startSucceeds = false
            advanceUntilIdle()

            viewModel.onServersShown()
            advanceUntilIdle()
            tester.releasedGroups shouldBe listOf(group.id)

            // :bg binds asynchronously across a process fork, so the list can
            // compose first. Without the release, that group's single launch run
            // was spent on a measurement that never happened.
            tester.startSucceeds = true
            viewModel.onServersShown()
            advanceUntilIdle()
            tester.testCallCount shouldBe 2
        }

    @Test
    fun `a metered network suppresses the launch run but leaves the manual action`() =
        runTest {
            tester.metered = true
            advanceUntilIdle()

            viewModel.onServersShown()
            advanceUntilIdle()
            tester.testCallCount shouldBe 0

            viewModel.onTestGroup(group.id)
            advanceUntilIdle()
            tester.testCallCount shouldBe 1
        }

    @Test
    fun `a connect in flight defers the launch run rather than cancelling it`() =
        runTest {
            tester.state = ConnectionState.Connecting(StartupStage.StartingCore)
            advanceUntilIdle()

            viewModel.onServersShown()
            advanceUntilIdle()
            tester.testCallCount shouldBe 0

            // Once the connect settles, the group's one launch run is still available.
            tester.state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10800)
            viewModel.onServersShown()
            advanceUntilIdle()
            tester.testCallCount shouldBe 1
        }
}
