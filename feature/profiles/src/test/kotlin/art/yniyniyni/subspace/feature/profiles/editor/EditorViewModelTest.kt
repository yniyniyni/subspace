// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.editor

import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
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
 * Covers what Task 21 builds: a field editor for `TYPED` profiles and a read-only-plus-rename
 * view for `RAW_JSON` ones (ARCHITECTURE.md §6 — passthrough execution is not implemented, so
 * a raw profile's bytes must never be reconstructed from a form).
 *
 * `StoredProfile` (the type this ViewModel reads back through [ProfileSource.profile]) has no
 * `identityHash` field on purpose — see
 * [ProfileRepository.update][art.yniyniyni.subspace.core.data.ProfileRepository.update]'s KDoc:
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

    private class FakeProfileSource(
        profiles: List<StoredProfile>,
        private val groups: List<ProfileGroup> = listOf(ProfileGroup(10L, "Local configs", emptyList())),
        /**
         * Ids that [update]/[move] must reject with `false` instead of applying — the JVM
         * stand-in for [ProfileEntity][art.yniyniyni.subspace.core.data.db.ProfileEntity]'s
         * unique `(groupId, identityHash)` index rejecting a real Room write. Empty by
         * default so every pre-existing test keeps its original "every write lands" shape.
         */
        private val rejectWritesFor: Set<Long> = emptySet(),
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
            rawJson: String?,
        ) = Unit

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
    fun `editing a typed profile rewrites its identity hash`() =
        runTest {
            val source = FakeProfileSource(listOf(typedProfile))
            val viewModel = EditorViewModel(source)

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
            val viewModel = EditorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()

            viewModel.state.value.fieldsEditable shouldBe false
            viewModel.state.value.rawJson shouldBe originalPastedText
        }

    @Test
    fun `renaming a raw json profile leaves its bytes untouched`() =
        runTest {
            val source = FakeProfileSource(listOf(rawJsonProfile))
            val viewModel = EditorViewModel(source)

            viewModel.load(rawJsonProfile.id)
            advanceUntilIdle()
            viewModel.onNameChanged("Renamed")
            viewModel.save()
            advanceUntilIdle()

            source.profile(rawJsonProfile.id)?.rawJson shouldBe originalPastedText
            source.lastRename shouldBe (rawJsonProfile.id to "Renamed")
            source.lastUpdate shouldBe null
        }

    @Test
    fun `loading an unknown profile id reports not found, not a crash`() =
        runTest {
            val source = FakeProfileSource(emptyList())
            val viewModel = EditorViewModel(source)

            viewModel.load(999L)
            advanceUntilIdle()

            viewModel.state.value.loading shouldBe false
            viewModel.state.value.exists shouldBe false
        }

    @Test
    fun `saving a typed profile with an invalid port does not persist and reports an error`() =
        runTest {
            val source = FakeProfileSource(listOf(typedProfile))
            val viewModel = EditorViewModel(source)
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
            val viewModel = EditorViewModel(source)
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
            val viewModel = EditorViewModel(source)
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
            val viewModel = EditorViewModel(source)
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
