// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.subscription

import art.yniyniyni.subspace.core.data.AddedSubscription
import art.yniyniyni.subspace.core.data.EffectiveValue
import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.data.sync.SyncResult
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionDetailViewModelTest {
    private class FakeProfileSource : ProfileSource {
        val subscriptions = MutableStateFlow(listOf(subscription(id = SUBSCRIPTION_ID)))
        private val groups =
            MutableStateFlow(
                listOf(ProfileGroup(id = GROUP_ID, name = "Provider", profiles = emptyList())),
            )
        private val providerValues =
            mapOf(
                KEY_INTERVAL to "6",
                KEY_TIMEOUT to "9",
                KEY_AUTO_UPDATE to "true",
                KEY_AUTO_UPDATE_OPEN to "true",
                KEY_USER_AGENT to "Provider Agent",
            )
        private val pins = MutableStateFlow<Map<String, String>>(emptyMap())
        val globalHwidEnabledState = MutableStateFlow(true)

        var pinned: Triple<Long, String, String>? = null
            private set
        var unpinned: Pair<Long, String>? = null
            private set
        var deletedId: Long? = null
            private set
        var userAgentOverride: String? = null
            private set
        var syncCalls = 0
            private set
        var syncGate: CompletableDeferred<Unit>? = null
        var deleteGate: CompletableDeferred<Unit>? = null
        val syncStarted = CompletableDeferred<Unit>()

        override fun observeGroups(query: String, protocol: String?): Flow<List<ProfileGroup>> = groups
        override val activeProfileId: StateFlow<Long?> = MutableStateFlow(null)
        override val globalHwidEnabled: StateFlow<Boolean> = globalHwidEnabledState
        override suspend fun setActiveProfile(id: Long?) = Unit
        override suspend fun renameGroup(id: Long, name: String) = Unit
        override suspend fun deleteGroup(id: Long) = Unit
        override suspend fun defaultGroupId(): Long = GROUP_ID
        override suspend fun import(profiles: List<Profile>, groupId: Long): Int = 0
        override suspend fun profile(id: Long): StoredProfile? = null
        override suspend fun rename(id: Long, name: String) = Unit
        override suspend fun move(id: Long, toGroupId: Long): Boolean = true
        override suspend fun update(id: Long, name: String, outbound: Outbound): Boolean = true
        override suspend fun addSubscription(url: String, name: String): AddedSubscription =
            AddedSubscription(SUBSCRIPTION_ID, created = true)
        override suspend fun syncSubscription(id: Long): SyncResult {
            syncCalls++
            syncStarted.complete(Unit)
            syncGate?.await()
            return SyncResult.Synced(0, 0, 0, 0, 0)
        }
        override suspend fun deleteSubscription(id: Long) {
            deleteGate?.await()
            deletedId = id
            subscriptions.value = emptyList()
        }

        override fun observeSubscriptions(): Flow<List<StoredSubscription>> = subscriptions
        override fun observeUserInfo(id: Long): Flow<String?> = MutableStateFlow(null)
        override fun observeEffective(id: Long, key: String, default: String?): Flow<EffectiveValue> =
            pins.map { currentPins ->
                val pin = currentPins[key]
                val provider = providerValues[key]
                EffectiveValue(key, pin ?: provider ?: default, provider, pin != null)
            }

        override suspend fun pin(id: Long, key: String, value: String) {
            pinned = Triple(id, key, value)
            pins.value += key to value
        }

        override suspend fun unpin(id: Long, key: String) {
            unpinned = id to key
            pins.value -= key
        }

        override suspend fun setHwidEnabled(id: Long, enabled: Boolean) = Unit
        override suspend fun setUserAgentOverride(id: Long, userAgent: String?) {
            userAgentOverride = userAgent
        }
    }

    private lateinit var source: FakeProfileSource
    private lateinit var viewModel: SubscriptionDetailViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source = FakeProfileSource()
        viewModel = SubscriptionDetailViewModel(source)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pinShowsTheProviderSuggestionAlongsideThePinnedValue() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onPinRow(KEY_INTERVAL, "24")
            advanceUntilIdle()

            source.pinned shouldBe Triple(SUBSCRIPTION_ID, KEY_INTERVAL, "24")
            viewModel.state.value.rows.first { it.row.key == KEY_INTERVAL }.row shouldBe
                SettingRowState(KEY_INTERVAL, "24", declinedProviderValue = "6", isPinned = true)
        }

    @Test
    fun unpinRestoresTheProviderValue() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onPinRow(KEY_INTERVAL, "24")
            advanceUntilIdle()
            viewModel.onUnpinRow(KEY_INTERVAL)
            advanceUntilIdle()

            source.unpinned shouldBe (SUBSCRIPTION_ID to KEY_INTERVAL)
            viewModel.state.value.rows.first { it.row.key == KEY_INTERVAL }.row shouldBe
                SettingRowState(KEY_INTERVAL, "6", declinedProviderValue = null, isPinned = false)
        }

    @Test
    fun deleteMarksTheScreenAsDeletedOnlyAfterTheSourceDeletesIt() =
        runTest {
            // "Only after" is an ordering claim, and asserting both facts after advanceUntilIdle()
            // could not check it: a ViewModel that set deleted = true *before* awaiting
            // deleteSubscription — the exact bug the name describes — passed identically. The gate
            // holds the source mid-delete so the intermediate state is observable.
            source.deleteGate = CompletableDeferred()
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onDelete()
            advanceUntilIdle()

            viewModel.state.value.deleted shouldBe false

            source.deleteGate?.complete(Unit)
            advanceUntilIdle()

            source.deletedId shouldBe SUBSCRIPTION_ID
            viewModel.state.value.deleted shouldBe true
        }

    @Test
    fun invalidPinnedValuesAreNotPersisted() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onPinRow(KEY_TIMEOUT, "16")
            advanceUntilIdle()

            source.pinned shouldBe null
        }

    @Test
    fun providerUserAgentRemainsVisibleAlongsideTheUserOverride() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            advanceUntilIdle()

            viewModel.state.value.providerUserAgent shouldBe "Provider Agent"
        }

    @Test
    fun globalHwidGateIsVisibleInTheDetailState() =
        runTest {
            viewModel.load(SUBSCRIPTION_ID)
            source.globalHwidEnabledState.value = false
            advanceUntilIdle()

            viewModel.state.value.globalHwidEnabled shouldBe false
        }

    @Test
    fun aValidUserAgentOverrideReachesTheSource() =
        runTest {
            // The positive case this file was missing entirely. Without it,
            // `invalidUserAgentOverridesAreNotPersisted` below asserts that a field the fixtures
            // never drive non-null is still null — which passes with onUserAgentOverrideChanged
            // implemented as an empty body.
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onUserAgentOverrideChanged("v2rayNG/1.8.5")
            advanceUntilIdle()

            source.userAgentOverride shouldBe "v2rayNG/1.8.5"
        }

    @Test
    fun invalidUserAgentOverridesAreNotPersisted() =
        runTest {
            // Starts from a *persisted* override, so "not persisted" means "the bad value did not
            // replace the good one" rather than "the field is still at its initial null". The
            // earlier version could not tell those apart.
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onUserAgentOverrideChanged("v2rayNG/1.8.5")
            advanceUntilIdle()

            viewModel.onUserAgentOverrideChanged("a".repeat(257))
            viewModel.onUserAgentOverrideChanged("Agent\nInjected")
            advanceUntilIdle()

            source.userAgentOverride shouldBe "v2rayNG/1.8.5"
        }

    @Test
    fun refreshNowDoesNotStartASecondSyncWhileTheFirstIsRunning() =
        runTest {
            source.syncGate = CompletableDeferred()
            viewModel.load(SUBSCRIPTION_ID)
            viewModel.onRefreshNow()
            source.syncStarted.await()
            viewModel.onRefreshNow()

            source.syncCalls shouldBe 1
            source.syncGate?.complete(Unit)
            advanceUntilIdle()
        }

    private companion object {
        const val SUBSCRIPTION_ID = 7L
        const val GROUP_ID = 9L
        const val KEY_INTERVAL = "profile-update-interval"
        const val KEY_TIMEOUT = "subscription-request-timeout"
        const val KEY_AUTO_UPDATE = "subscription-auto-update-enable"
        const val KEY_AUTO_UPDATE_OPEN = "subscription-auto-update-open-enable"
        const val KEY_USER_AGENT = "change-user-agent"

        fun subscription(id: Long) =
            StoredSubscription(
                id = id,
                groupId = GROUP_ID,
                url = "https://panel.example/sub/token",
                userAgentOverride = null,
                hwidEnabled = true,
                lastFetchedAt = null,
                lastFetchStatus = null,
                lastFetchDetail = null,
            )
    }
}
