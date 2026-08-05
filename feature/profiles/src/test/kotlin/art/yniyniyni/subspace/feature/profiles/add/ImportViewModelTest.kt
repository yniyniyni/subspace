// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers what Task 19 builds: turning pasted or imported text into stored
 * profiles via [SubscriptionParser][art.yniyniyni.subspace.core.parser.SubscriptionParser]
 * and [ProfileSource.import], honestly reporting both halves of the resulting
 * `ParseOutcome` (§7) rather than silently dropping the failing entries
 * (§10.4).
 *
 * `viewModelScope` needs a Main dispatcher outside Android, hence
 * [UnconfinedTestDispatcher] — same setup as [art.yniyniyni.subspace.feature.home.HomeViewModelTest]
 * and [art.yniyniyni.subspace.feature.profiles.list.ServersViewModelTest]. But
 * [ImportViewModel.import] hops to a genuine `Dispatchers.Default` background
 * thread for the parse (see its own KDoc for why), which the test scheduler
 * [advanceUntilIdle] drains does not control — a bare `advanceUntilIdle()`
 * after [ImportViewModel.import] is not enough and the very first version of
 * this file proved it, failing all four tests with `imported=0` instead of
 * the real count (assertions ran before the background hop resumed). Fixed
 * the same way the M1 predecessor's test did (`git show a2e3bf4`,
 * `HomeViewModelTest`'s old `parseInput` coverage): also await
 * `state.first { it.completed }`, which suspends on the real cross-thread
 * resume rather than the virtual clock.
 *
 * Backtick test names keep the spaces the brief wrote them with, same as
 * [art.yniyniyni.subspace.feature.home.HomeViewModelTest]: this file runs as
 * a plain JVM unit test (`:feature:profiles:testDebugUnitTest`), never
 * through D8/dexing, so the DEX-040 synthetic-class-name restriction that
 * forces camelCase in this repo's *instrumented* tests does not apply here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    /**
     * A minimal but complete `:core:xray`-emittable VLESS body: `protocol`
     * alone (the brief's own illustrative fixture) has no `settings.vnext`,
     * so `parseXrayJson` reports it as malformed JSON rather than a profile —
     * this adds the address/port/user `parseVlessOutbound` requires while
     * keeping the brief's own irregular spacing (`"outbounds" :`, double
     * spaces around the braces) to prove that formatting survives storage
     * unchanged. 198.51.100.7 is RFC 5737 documentation space, matching
     * FailureTextTest's own convention for "an address that must never leak."
     */
    private val validRawJson =
        """{  "outbounds" : [ { "protocol":"vless","settings":{"vnext":[{"address":"198.51.100.7",""" +
            """"port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111"}]}]} } ]  }"""

    private class FakeProfileSource : ProfileSource {
        private val stored = mutableMapOf<Long, StoredProfile>()
        private var nextId = 1L

        var lastImportedGroupId: Long? = null
            private set

        override fun observeGroups(
            query: String,
            protocol: String?,
        ): Flow<List<ProfileGroup>> = MutableStateFlow(emptyList())

        override val activeProfileId: Flow<Long?> = MutableStateFlow(null)

        override suspend fun setActiveProfile(id: Long?) = Unit

        override suspend fun renameGroup(
            id: Long,
            name: String,
        ) = Unit

        override suspend fun deleteGroup(id: Long) = Unit

        // Mirrors ProfileRepository.defaultGroupId()'s "Local configs" group
        // (spec D3) — a single fixed id is enough here, nothing in this test
        // exercises group creation itself.
        override suspend fun defaultGroupId(): Long = 1L

        override suspend fun import(
            profiles: List<Profile>,
            groupId: Long,
            rawJson: String?,
        ) {
            lastImportedGroupId = groupId
            profiles.forEach { profile ->
                val id = nextId++
                stored[id] =
                    StoredProfile(
                        id = id,
                        groupId = groupId,
                        kind = if (rawJson == null) ProfileKind.TYPED else ProfileKind.RAW_JSON,
                        name = profile.name,
                        protocol = "vless",
                        address = profile.outbound.address,
                        port = profile.outbound.port,
                        transport = "tcp",
                        outbound = profile.outbound,
                        rawJson = rawJson,
                        lastConnectedAt = null,
                        lastError = null,
                    )
            }
        }

        override suspend fun profile(id: Long): StoredProfile? = stored[id]

        // Not exercised here — EditorViewModelTest (Task 21) owns real
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
    fun `a partial import reports both halves`() =
        runTest {
            val source = FakeProfileSource()
            val viewModel = ImportViewModel(source)

            val validLines =
                (1..197).map { i ->
                    "vless://11111111-1111-1111-1111-111111111111@host$i.example.com:443#server$i"
                }
            val brokenLines =
                listOf(
                    // Malformed UUID -> MissingCredential.
                    "vless://not-a-uuid@bad.example.com:443#broken1",
                    // Port out of range -> InvalidPort.
                    "vless://11111111-1111-1111-1111-111111111111@bad.example.com:99999#broken2",
                    // No "://" at all -> UnknownScheme.
                    "not-a-link-at-all",
                )
            val twoHundredLinesOfWhichThreeAreBroken = (validLines + brokenLines).joinToString("\n")

            viewModel.import(twoHundredLinesOfWhichThreeAreBroken)
            advanceUntilIdle()
            viewModel.state.first { it.completed }

            viewModel.state.value.imported shouldBe 197
            viewModel.state.value.failures.size shouldBe 3
        }

    @Test
    fun `raw json is stored byte-for-byte`() =
        runTest {
            val repository = FakeProfileSource()
            val viewModel = ImportViewModel(repository)

            viewModel.import(validRawJson)
            advanceUntilIdle()
            viewModel.state.first { it.completed }

            repository.profile(1)?.rawJson shouldBe validRawJson
        }

    @Test
    fun `a clean import lands in the default group`() =
        runTest {
            val repository = FakeProfileSource()
            val viewModel = ImportViewModel(repository)

            viewModel.import("vless://11111111-1111-1111-1111-111111111111@host.example.com:443#one")
            advanceUntilIdle()
            viewModel.state.first { it.completed }

            repository.lastImportedGroupId shouldBe repository.defaultGroupId()
        }

    @Test
    fun `an empty paste reports zero imported and one failure, not a crash`() =
        runTest {
            val repository = FakeProfileSource()
            val viewModel = ImportViewModel(repository)

            viewModel.import("")
            advanceUntilIdle()
            viewModel.state.first { it.completed }

            viewModel.state.value.imported shouldBe 0
            viewModel.state.value.failures.size shouldBe 1
            viewModel.state.value.completed shouldBe true
        }

    // Fix round 1, finding 3: `input` moved from AddServerSheet's own
    // `rememberSaveable` into ImportState (see that class's own KDoc for why)
    // so it can be driven and cleared here without Compose at all.

    @Test
    fun `typing into the paste field updates state input`() =
        runTest {
            val viewModel = ImportViewModel(FakeProfileSource())

            viewModel.onInputChanged("vless://pasted")

            viewModel.state.value.input shouldBe "vless://pasted"
        }

    @Test
    fun `a successful import clears the pasted input`() =
        runTest {
            val repository = FakeProfileSource()
            val viewModel = ImportViewModel(repository)
            val link = "vless://11111111-1111-1111-1111-111111111111@host.example.com:443#one"
            viewModel.onInputChanged(link)

            viewModel.import(viewModel.state.value.input)
            advanceUntilIdle()
            viewModel.state.first { it.completed }

            viewModel.state.value.input shouldBe ""
        }

    @Test
    fun `a fully failed import keeps the pasted input so the user can fix it`() =
        runTest {
            val viewModel = ImportViewModel(FakeProfileSource())
            val brokenPaste = "not-a-link-at-all"
            viewModel.onInputChanged(brokenPaste)

            viewModel.import(viewModel.state.value.input)
            advanceUntilIdle()
            viewModel.state.first { it.completed }

            viewModel.state.value.imported shouldBe 0
            viewModel.state.value.input shouldBe brokenPaste
        }

    // Fix round 1, finding 2: the file-read path (AddServerSheet.kt) reports
    // a null/thrown read through these two ImportViewModel entry points
    // rather than doing nothing or crashing the coroutine.

    @Test
    fun `reportFileReadFailure surfaces a visible failure and leaves busy false`() =
        runTest {
            val viewModel = ImportViewModel(FakeProfileSource())
            viewModel.beginFileRead()

            viewModel.reportFileReadFailure()

            viewModel.state.value.fileReadFailed shouldBe true
            viewModel.state.value.busy shouldBe false
        }

    @Test
    fun `beginFileRead resets a stale completed result but keeps the pasted input`() =
        runTest {
            val repository = FakeProfileSource()
            val viewModel = ImportViewModel(repository)
            viewModel.import("vless://11111111-1111-1111-1111-111111111111@host.example.com:443#one")
            advanceUntilIdle()
            viewModel.state.first { it.completed }
            viewModel.onInputChanged("kept across the file pick")

            viewModel.beginFileRead()

            viewModel.state.value.completed shouldBe false
            viewModel.state.value.busy shouldBe true
            viewModel.state.value.input shouldBe "kept across the file pick"
        }
}
