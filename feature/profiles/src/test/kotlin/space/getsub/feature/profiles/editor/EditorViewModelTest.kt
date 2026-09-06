// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.editor

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
import space.getsub.core.data.PendingRoutingConversion
import space.getsub.core.data.ProfileGroup
import space.getsub.core.data.ProfileKind
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.sync.SyncResult
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.VlessOutbound
import space.getsub.core.parser.OverrideBlocker
import space.getsub.core.parser.PassthroughAdvisory
import space.getsub.core.parser.PassthroughRejection
import space.getsub.feature.profiles.ProfileSource

/**
 * Covers what Task 21 builds: a field editor for `TYPED` profiles and a read-only-plus-rename
 * view for `RAW_JSON` ones (ARCHITECTURE.md §6 — passthrough execution is not implemented, so
 * a raw profile's bytes must never be reconstructed from a form).
 *
 * `StoredProfile` (the type this ViewModel reads back through [ProfileSource.profile]) has no
 * `identityHash` field on purpose — see
 * [ProfileRepository.update][space.getsub.core.data.ProfileRepository.update]'s KDoc:
 * that column is a `:core:data` storage concern, not something a caller reads back and
 * compares. The brief's own "rewrites its identity hash" assertion is therefore proven at two
 * layers instead of one: this file proves `EditorViewModel.save()` calls
 * [ProfileSource.update] with the whole edited [Outbound] (the input identity hashing is a
 * pure function of), and `core/data`'s own `ProfileRepositoryTest.updatingATypedProfileRewritesItsIdentityHash`
 * proves that call actually rewrites the real `identityHash` column in Room.
 *
 * `viewModelScope` needs a Main dispatcher outside Android, hence [UnconfinedTestDispatcher] —
 * same setup as every other ViewModel test in this module.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val typedOutbound =
        VlessOutbound(
            address = "198.51.100.7",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            flow = null,
            stream = StreamSettings(network = "tcp", security = Security.None),
        )

    private val typedProfile =
        StoredProfile(
            id = 1L,
            groupId = 10L,
            kind = ProfileKind.TYPED,
            name = "Typed server",
            protocol = "vless",
            address = typedOutbound.address,
            port = typedOutbound.port,
            transport = "tcp · 443",
            outbound = typedOutbound,
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

    private val originalPastedText = """{  "outbounds" : [ { "protocol":"vless" } ]  }"""

    /** A raw config whose own `routing` block `convertXrayRouting` (Task 13) can carry. */
    private val routableRawJson =
        """{"routing":{"rules":[{"domain":["a.com"],"outboundTag":"direct"}]},""" +
            """"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""

    private val rawJsonProfile =
        StoredProfile(
            id = 2L,
            groupId = 10L,
            kind = ProfileKind.RAW_JSON,
            name = "Raw server",
            protocol = "vless",
            address = "198.51.100.8",
            port = 443,
            transport = "tcp · 443",
            outbound =
            VlessOutbound(
                address = "198.51.100.8",
                port = 443,
                uuid = "22222222-2222-2222-2222-222222222222",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
            rawJson = originalPastedText,
            lastConnectedAt = null,
            lastError = null,
        )

    /** Same shape as [rawJsonProfile], but its own `routing` block converts (Task 15). */
    private val routableRawJsonProfile = rawJsonProfile.copy(id = 3L, rawJson = routableRawJson)

    /**
     * A raw config whose `routing.rules` array is non-empty, but every rule is dropped —
     * `protocol` is one of [space.getsub.core.parser.routing.ConversionDrop.UnsupportedMatcher]'s
     * keys, so this rule carries no `domain`/`ip` entry into any bucket. Fix round 1 (Important
     * 1): `convertXrayRouting` still returns non-null here (a non-null `RoutingConversion` with
     * an empty bucket map), so `canConvertRouting` must not gate on non-null alone — offering the
     * action for this config would hand the review sheet a profile that replaces this config's
     * routing with nothing once activated.
     */
    private val allDroppedRawJson =
        """{"routing":{"rules":[{"protocol":["bittorrent"],"outboundTag":"block"}]},""" +
            """"outbounds":[{"tag":"block","protocol":"blackhole"}]}"""

    private val allDroppedRawJsonProfile = rawJsonProfile.copy(id = 4L, rawJson = allDroppedRawJson)

    /**
     * The target panel's own balancer entry, reduced to the defect (device record F9):
     * `fallbackTag` names an outbound the document never defines.
     */
    private val danglingRoutingRawJson =
        """{"routing":{"rules":[{"type":"field","network":"tcp,udp","balancerTag":"Auto_Balancer"}],""" +
            """"balancers":[{"tag":"Auto_Balancer","selector":["proxy"],"fallbackTag":"proxy"}]},""" +
            """"outbounds":[{"tag":"proxy-auto","protocol":"vless"}]}"""

    private val danglingRoutingRawJsonProfile = rawJsonProfile.copy(id = 5L, rawJson = danglingRoutingRawJson)

    private val balancerRawJsonProfile =
        rawJsonProfile.copy(
            id = 6L,
            rawJson =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["proxy"] } ] }
            }
            """.trimIndent(),
        )

    /**
     * A RAW_JSON row the analyser rejected. Its bytes would resolve to
     * `Unresolvable(NoResolvableTarget)`, so it is exactly the shape that used to
     * show an override-blocker message it had no business showing.
     */
    private val rejectedRawJsonProfile =
        rawJsonProfile.copy(
            id = 8L,
            passthroughRejection = PassthroughRejection.SeveralServers,
            rawJson =
            """
            {
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
            """.trimIndent(),
        )

    private val emptyBalancerRawJsonProfile =
        rawJsonProfile.copy(
            id = 7L,
            rawJson =
            """
            {
              "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ],
              "routing": { "balancers": [ { "tag": "B", "selector": ["nothing"] } ] }
            }
            """.trimIndent(),
        )

    private class FakeProfileSource(
        profiles: List<StoredProfile>,
        private val groups: List<ProfileGroup> = listOf(ProfileGroup(10L, "Local configs", emptyList())),
        /**
         * Ids that [update]/[move] must reject with `false` instead of applying — the JVM
         * stand-in for [ProfileEntity][space.getsub.core.data.db.ProfileEntity]'s
         * unique `(groupId, identityHash)` index rejecting a real Room write. Empty by
         * default so every pre-existing test keeps its original "every write lands" shape.
         */
        private val rejectWritesFor: Set<Long> = emptySet(),
        /**
         * Backs [routingOverridesPassthrough] below — Task 10 review, Critical 2: the real
         * value comes from `SettingsRepository.activeRoutingRuleSetId`/`.dnsResolver` combined
         * (see `ProfileSource.BoundProfileSource`'s own override), which this fake stands in
         * for as a plain constructor flag since [EditorViewModel.load] only ever reads one
         * snapshot of it via `.first()`.
         */
        private val routingOverridesPassthroughValue: Boolean = false,
    ) : ProfileSource {
        private val stored = profiles.associateBy { it.id }.toMutableMap()

        var lastUpdate: Triple<Long, String, Outbound>? = null
            private set
        var lastRename: Pair<Long, String>? = null
            private set
        var lastMove: Pair<Long, Long>? = null
            private set

        override fun observeGroups(
            query: String,
            protocol: String?,
        ): Flow<List<ProfileGroup>> = MutableStateFlow(groups)

        override val activeProfileId: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
        override val globalHwidEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val routingOverridesPassthrough: Flow<Boolean> =
            MutableStateFlow(routingOverridesPassthroughValue).asStateFlow()

        override suspend fun setActiveProfile(id: Long?) = Unit

        override suspend fun renameGroup(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun deleteGroup(id: Long) = Unit

        override suspend fun defaultGroupId(): Long = 10L

        override suspend fun import(
            profiles: List<Profile>,
            groupId: Long,
        ) = 0

        override suspend fun profile(id: Long): StoredProfile? = stored[id]

        override suspend fun rename(
            id: Long,
            name: String,
        ) {
            lastRename = id to name
            stored[id]?.let { stored[id] = it.copy(name = name) }
        }

        override suspend fun move(
            id: Long,
            toGroupId: Long,
        ): Boolean {
            lastMove = id to toGroupId
            if (id in rejectWritesFor) return false
            stored[id]?.let { stored[id] = it.copy(groupId = toGroupId) }
            return true
        }

        override suspend fun update(
            id: Long,
            name: String,
            outbound: Outbound,
        ): Boolean {
            lastUpdate = Triple(id, name, outbound)
            if (id in rejectWritesFor) return false
            stored[id]?.let { stored[id] = it.copy(name = name, address = outbound.address, port = outbound.port) }
            return true
        }

        // Not exercised here — this fixture is EditorViewModelTest's own, and
        // nothing in the editor screen touches subscriptions. See
        // ImportViewModelTest's identical stub for the fuller rationale.
        override suspend fun addSubscription(
            url: String,
            name: String,
        ) = AddedSubscription(0L, created = true)

        override suspend fun syncSubscription(id: Long): SyncResult = SyncResult.Synced(0, 0, 0, 0, 0)

        override suspend fun deleteSubscription(id: Long) = Unit

        // Not exercised — same reasoning as addSubscription above. Task 14's
        // real coverage lives in ServersViewModelTest.
        override fun observeSubscriptions(): Flow<List<StoredSubscription>> = MutableStateFlow(emptyList())

        override fun observeUserInfo(id: Long): Flow<String?> = MutableStateFlow(null)

        // Not exercised — same reasoning as addSubscription above. SubscriptionDetailViewModelTest
        // (Task 15) owns real coverage of these five.
        override fun observeEffective(
            id: Long,
            key: String,
            default: String?,
        ): Flow<EffectiveValue> = MutableStateFlow(EffectiveValue(key, default, null, isPinned = false))

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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Constructs [EditorViewModel] with [EditorViewModel.conversionDispatcher] pinned to
     * [Dispatchers.Unconfined] rather than the real [Dispatchers.Default] `load()` uses in
     * production.
     *
     * Fix round 1, Minor: `load()` moved its `canConvertRouting` parse off Main via a genuine
     * `withContext(Dispatchers.Default)` hop. Left at its production default, that hop escapes
     * [UnconfinedTestDispatcher]'s virtual-time control — the real background thread resumes
     * `load()`'s coroutine on its own schedule, so `advanceUntilIdle()` (which only drains the
     * *test* dispatcher's queue) returns before `_state.value` is actually assigned, and every
     * test in this file that asserts immediately after `load()` observes a stale default
     * `EditorState()`. Every other suspend call `load()` makes runs against a trivial
     * [ProfileSource] fake with no real suspension; this dispatcher is the one exception, so it
     * gets the one override, keeping this file's `advanceUntilIdle()`-then-assert pattern intact
     * for every test rather than rewriting the file's synchronisation style.
     *
     * Fix round 2: [EditorViewModel.conversionDispatcher] gained `private set`, so this goes
     * through [EditorViewModel.setConversionDispatcherForTesting] rather than a direct
     * assignment — see that function's KDoc.
     */
    private fun editorViewModel(
        source: ProfileSource,
        pending: PendingRoutingConversion = PendingRoutingConversion(),
    ): EditorViewModel =
        EditorViewModel(source, pending).apply { setConversionDispatcherForTesting(Dispatchers.Unconfined) }

    @Test
    fun `editing a typed profile rewrites its identity hash`() =
        runTest {
            val source = FakeProfileSource(listOf(typedProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(typedProfile.id)
            advanceUntilIdle()
            viewModel.onAddressChanged("198.51.100.99")
            viewModel.save()
            advanceUntilIdle()

            // ProfileRepository.update() recomputes identityHashOf(outbound) from exactly
            // this value (proven against the real Room column by
            // ProfileRepositoryTest.updatingATypedProfileRewritesItsIdentityHash) — a
            // changed address here is a changed identity there.
            source.lastUpdate?.third?.address shouldBe "198.51.100.99"
            source.profile(typedProfile.id)?.address shouldBe "198.51.100.99"
        }

    @Test
    fun `a raw json profile is not field-editable`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.fieldsEditable shouldBe false
            viewModel.state.value.rawJson shouldBe originalPastedText
        }

    // Task 10 review, Critical 2: EditorState.runsAsWritten/passthroughRejection come
    // straight off the loaded StoredProfile (unchanged this round), but
    // routingOverridesThisConfig is new wiring — load() now reads
    // ProfileSource.routingOverridesPassthrough rather than defaulting to false forever.
    @Test
    fun `an eligible raw json profile carries runsAsWritten and no rejection`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.runsAsWritten shouldBe true
            viewModel.state.value.passthroughRejection shouldBe null
        }

    // Task 3, M7 device fixes: the target panel's balancer entry connects and carries
    // nothing (device record F9) — analysePassthrough reports it as an advisory, and load()
    // must carry that into EditorState for the screen to render.
    @Test
    fun `a raw json profile with a dangling fallbackTag carries the advisory`() =
        runTest {
            val source = FakeProfileSource(listOf(danglingRoutingRawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(danglingRoutingRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.advisories shouldBe listOf(PassthroughAdvisory.DanglingRoutingReference)
        }

    @Test
    fun `a balancer config that resolves carries no override blocker`() =
        runTest {
            val source = FakeProfileSource(listOf(balancerRawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(balancerRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.overrideBlocker shouldBe null
        }

    @Test
    fun `a balancer that selects nothing is reported to the user`() =
        runTest {
            // A6: the core accepts this config and then silently drops every
            // packet, so this message is the only warning the user will get.
            val source = FakeProfileSource(listOf(emptyBalancerRawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(emptyBalancerRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.overrideBlocker shouldBe OverrideBlocker.BalancerSelectsNothing
        }

    // Branch review I1: `rawJson` is populated for every RAW_JSON row, rejected ones
    // included, so an ungated blocker told the user "this profile still connects, but
    // those settings will not apply" about a row that neither runs as written nor
    // escapes the app's routing — it gets both, via the typed projection.
    @Test
    fun `a rejected raw json row carries no override blocker`() =
        runTest {
            val source = FakeProfileSource(listOf(rejectedRawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(rejectedRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.runsAsWritten shouldBe false
            viewModel.state.value.overrideBlocker shouldBe null
        }

    // Fix round 1, Minor 5: renamed from "...whose references all resolve carries no
    // advisories" — rawJsonProfile's own rawJson carries no `routing` block at all, so there
    // is nothing to resolve; the honest claim this test pins is the absence of a routing block
    // producing an empty advisory list, not a resolved reference.
    @Test
    fun `a raw json profile with no routing block carries no advisories`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.advisories shouldBe emptyList()
        }

    @Test
    fun `load reads routingOverridesThisConfig from the routing-overrides signal`() =
        runTest {
            val overridingSource = FakeProfileSource(listOf(rawJsonProfile), routingOverridesPassthroughValue = true)
            val overridingViewModel = editorViewModel(overridingSource)
            overridingViewModel.load(rawJsonProfile.id)
            advanceUntilIdle()
            overridingViewModel.state.value.routingOverridesThisConfig shouldBe true

            val quietSource = FakeProfileSource(listOf(rawJsonProfile), routingOverridesPassthroughValue = false)
            val quietViewModel = editorViewModel(quietSource)
            quietViewModel.load(rawJsonProfile.id)
            advanceUntilIdle()
            quietViewModel.state.value.routingOverridesThisConfig shouldBe false
        }

    @Test
    fun `renaming a raw json profile leaves its bytes untouched`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()
            viewModel.onNameChanged("Renamed")
            viewModel.save()
            advanceUntilIdle()

            source.profile(rawJsonProfile.id)?.rawJson shouldBe originalPastedText
            source.lastRename shouldBe (rawJsonProfile.id to "Renamed")
            source.lastUpdate shouldBe null
        }

    // Task 15: the entry point that makes Task 13's convertXrayRouting and Task 14's
    // startConversionReview reachable in production.
    @Test
    fun `canConvertRouting is true when the config's own routing block converts`() =
        runTest {
            val source = FakeProfileSource(listOf(routableRawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(routableRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.canConvertRouting shouldBe true
        }

    @Test
    fun `canConvertRouting is false when the config has nothing convertXrayRouting can carry`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.canConvertRouting shouldBe false
        }

    @Test
    fun `convertRouting offers the converted profile to the pending holder`() =
        runTest {
            val source = FakeProfileSource(listOf(routableRawJsonProfile))
            val pending = PendingRoutingConversion()
            val viewModel = editorViewModel(source, pending)
            viewModel.load(routableRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.convertRouting()

            pending.conversion.value?.profile?.name shouldBe routableRawJsonProfile.name
        }

    @Test
    fun `convertRouting does nothing when there is nothing to convert`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val pending = PendingRoutingConversion()
            val viewModel = editorViewModel(source, pending)
            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()

            viewModel.convertRouting()

            pending.conversion.value shouldBe null
        }

    // Fix round 1, Important 1: convertXrayRouting returns non-null (a RoutingConversion with an
    // empty bucket map) for a config whose routing.rules is non-empty but whose every rule is
    // dropped — canConvertRouting must not offer the action for that config, or confirming it
    // would replace this config's own routing with nothing once activated.
    @Test
    fun `canConvertRouting is false when every rule in routing is dropped`() =
        runTest {
            val source = FakeProfileSource(listOf(allDroppedRawJsonProfile))
            val viewModel = editorViewModel(source)

            viewModel.load(allDroppedRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.canConvertRouting shouldBe false
        }

    @Test
    fun `convertRouting does nothing when every rule in routing is dropped`() =
        runTest {
            val source = FakeProfileSource(listOf(allDroppedRawJsonProfile))
            val pending = PendingRoutingConversion()
            val viewModel = editorViewModel(source, pending)
            viewModel.load(allDroppedRawJsonProfile.id)
            advanceUntilIdle()

            viewModel.convertRouting()

            pending.conversion.value shouldBe null
        }

    @Test
    fun `loading an unknown profile id reports not found, not a crash`() =
        runTest {
            val source = FakeProfileSource(emptyList())
            val viewModel = editorViewModel(source)

            viewModel.load(999L)
            advanceUntilIdle()

            viewModel.state.value.loading shouldBe false
            viewModel.state.value.exists shouldBe false
        }

    @Test
    fun `saving a typed profile with an invalid port does not persist and reports an error`() =
        runTest {
            val source = FakeProfileSource(listOf(typedProfile))
            val viewModel = editorViewModel(source)
            viewModel.load(typedProfile.id)
            advanceUntilIdle()

            viewModel.onPortChanged("70000")
            viewModel.save()
            advanceUntilIdle()

            source.lastUpdate shouldBe null
            viewModel.state.value.errors.containsKey(EditorFieldKey.Port) shouldBe true
            viewModel.state.value.saved shouldBe false
        }

    @Test
    fun `moving a typed profile to a different group calls move`() =
        runTest {
            val source =
                FakeProfileSource(
                    listOf(typedProfile),
                    groups =
                    listOf(
                        ProfileGroup(10L, "Local configs", emptyList()),
                        ProfileGroup(20L, "Other", emptyList()),
                    ),
                )
            val viewModel = editorViewModel(source)
            viewModel.load(typedProfile.id)
            advanceUntilIdle()

            viewModel.onGroupChanged(20L)
            viewModel.save()
            advanceUntilIdle()

            source.lastMove shouldBe (typedProfile.id to 20L)
        }

    // Task 21 fix round 1: ProfileEntity's unique (groupId, identityHash) index (§4.2)
    // makes ProfileDao.updateProfile's default ABORT conflict strategy throw
    // SQLiteConstraintException when an edited outbound collides with a sibling profile
    // already in the group. Pre-fix, EditorViewModel.save() called ProfileSource.update()
    // inside viewModelScope.launch with no try/catch, so that exception was uncaught and
    // crashed the app (ARCHITECTURE.md §10.4). FakeProfileSource's rejectWritesFor stands
    // in for that real constraint violation without needing Room.
    @Test
    fun `saving a typed profile into a colliding identity reports duplicate identity, not a crash`() =
        runTest {
            val source = FakeProfileSource(listOf(typedProfile), rejectWritesFor = setOf(typedProfile.id))
            val viewModel = editorViewModel(source)
            viewModel.load(typedProfile.id)
            advanceUntilIdle()

            viewModel.onAddressChanged("198.51.100.99")
            viewModel.save()
            advanceUntilIdle()

            viewModel.state.value.duplicateIdentity shouldBe true
            viewModel.state.value.saved shouldBe false
            // The rejected write must not have landed — the stored address is still the
            // one the profile loaded with, not the colliding draft.
            source.profile(typedProfile.id)?.address shouldBe typedOutbound.address
        }

    // move() is exposed to the same unique index as update() — moving a profile into a
    // group that already holds an identical outbound. This is a second call site with the
    // same failure class, not a duplicate of the test above: save() calls move() before
    // update() when the group changed, and this proves that earlier call is guarded too.
    @Test
    fun `moving a typed profile into a group that already holds it reports duplicate identity, not a crash`() =
        runTest {
            val source =
                FakeProfileSource(
                    listOf(typedProfile),
                    groups =
                    listOf(
                        ProfileGroup(10L, "Local configs", emptyList()),
                        ProfileGroup(20L, "Other", emptyList()),
                    ),
                    rejectWritesFor = setOf(typedProfile.id),
                )
            val viewModel = editorViewModel(source)
            viewModel.load(typedProfile.id)
            advanceUntilIdle()

            viewModel.onGroupChanged(20L)
            viewModel.save()
            advanceUntilIdle()

            viewModel.state.value.duplicateIdentity shouldBe true
            viewModel.state.value.saved shouldBe false
            // The rejected move must not have landed — still in the original group.
            source.profile(typedProfile.id)?.groupId shouldBe typedProfile.groupId
            // update() must never be reached once move() itself was rejected.
            source.lastUpdate shouldBe null
        }
}
