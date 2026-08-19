// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.InstalledApp
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerAppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /**
     * Models the **raw** store, the way `SettingsRepository` does: a mode cell and
     * a packages cell, each written as given and read back as written.
     *
     * The distinction is the whole of the round-trip bug this fake used to hide.
     * `PerAppRepository` exposes two flows over these two cells —
     * `selection`, which collapses `Off` and the empty deny-list to no packages,
     * and `userSelection`, which does not. A fake that stored whatever `apply`
     * was handed and served it back as `selection` agreed with the ViewModel's
     * old mistaken assumption instead of with Room, so the ViewModel could seed
     * from the collapsing flow and no test noticed. `PerAppSource` now offers the
     * raw flow only, and this stores raw.
     */
    private class FakeSource(
        private val installedApps: List<InstalledApp>,
        initial: PerAppSelection = PerAppSelection.OFF,
    ) : PerAppSource {
        /** The two Room cells, raw. Nothing here collapses anything. */
        val stored = MutableStateFlow(initial)
        var applyCount = 0
        val tunnelActive = MutableStateFlow(false)
        var reapplyCount = 0

        override val userSelection = stored

        override val isTunnelActive = tunnelActive

        override suspend fun installed(): List<InstalledApp> = installedApps

        override suspend fun apply(mode: PerAppMode, packages: Set<String>) {
            applyCount++
            stored.value = PerAppSelection(mode, packages)
        }

        override suspend fun reapply() {
            reapplyCount++
        }

        /**
         * What `PerAppRepository.selection` would report for the same cells — the
         * effective set the tunnel would be built with. Not part of
         * [PerAppSource]; kept here so a test can assert that the picker survives
         * a state the *service* sees as OFF.
         */
        fun effective(): PerAppSelection {
            val raw = stored.value
            val collapses =
                raw.mode == PerAppMode.Off ||
                    (raw.mode == PerAppMode.DenyList && raw.packages.isEmpty())
            return if (collapses) PerAppSelection.OFF else raw
        }
    }

    private val apps =
        listOf(
            InstalledApp("com.example.bank", "Bank"),
            InstalledApp("com.example.maps", "Maps"),
        )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun everyInstalledAppBecomesARow() = runTest(dispatcher) {
        val model = PerAppViewModel(FakeSource(apps))
        dispatcher.scheduler.advanceUntilIdle()

        model.state.value.rows.map { it.packageName } shouldBe
            listOf("com.example.bank", "com.example.maps")
        model.state.value.rows.all { it.isInstalled } shouldBe true
        model.state.value.rows.none { it.isSelected } shouldBe true
    }

    @Test
    fun togglingMarksTheRowSelectedAndTheDraftDirty() = runTest(dispatcher) {
        val model = PerAppViewModel(FakeSource(apps))
        dispatcher.scheduler.advanceUntilIdle()

        model.toggle("com.example.bank")

        model.state.value.rows.first { it.packageName == "com.example.bank" }.isSelected shouldBe true
        model.state.value.isDirty shouldBe true
    }

    // Nothing is written until save(): a half-finished edit must not reach the
    // tunnel, and on this screen reaching the tunnel means a reconnect.
    @Test
    fun editingWritesNothingUntilSave() = runTest(dispatcher) {
        val source = FakeSource(apps)
        val model = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        model.setMode(PerAppMode.DenyList)
        model.toggle("com.example.bank")
        dispatcher.scheduler.advanceUntilIdle()

        source.applyCount shouldBe 0

        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        source.applyCount shouldBe 1
        source.stored.value shouldBe PerAppSelection(PerAppMode.DenyList, setOf("com.example.bank"))
    }

    @Test
    fun savingClearsTheDirtyFlag() = runTest(dispatcher) {
        val model = PerAppViewModel(FakeSource(apps))
        dispatcher.scheduler.advanceUntilIdle()

        model.toggle("com.example.bank")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        model.state.value.isDirty shouldBe false
    }

    @Test
    fun discardingRestoresTheStoredSelection() = runTest(dispatcher) {
        val stored = PerAppSelection(PerAppMode.DenyList, setOf("com.example.maps"))
        val model = PerAppViewModel(FakeSource(apps, stored))
        dispatcher.scheduler.advanceUntilIdle()

        model.toggle("com.example.bank")
        model.discard()

        model.state.value.isDirty shouldBe false
        model.state.value.rows.first { it.packageName == "com.example.maps" }.isSelected shouldBe true
        model.state.value.rows.first { it.packageName == "com.example.bank" }.isSelected shouldBe false
    }

    // §7.2: selected, then uninstalled. Shown, flagged, removable by hand — never
    // dropped for the user. §8's skip-and-continue already makes it harmless at
    // connect time, so there is nothing to protect them from.
    @Test
    fun aSelectedPackageThatIsGoneIsShownAsNotInstalledAndKept() = runTest(dispatcher) {
        val stored = PerAppSelection(PerAppMode.DenyList, setOf("com.example.deleted"))
        val model = PerAppViewModel(FakeSource(apps, stored))
        dispatcher.scheduler.advanceUntilIdle()

        val ghost = model.state.value.rows.first { it.packageName == "com.example.deleted" }
        ghost.isInstalled shouldBe false
        ghost.isSelected shouldBe true

        model.save()
        dispatcher.scheduler.advanceUntilIdle()
        model.state.value.rows.any { it.packageName == "com.example.deleted" } shouldBe true
    }

    @Test
    fun searchFiltersByLabelCaseInsensitively() = runTest(dispatcher) {
        val model = PerAppViewModel(FakeSource(apps))
        dispatcher.scheduler.advanceUntilIdle()

        model.search("ba")

        model.state.value.rows.map { it.packageName } shouldBe listOf("com.example.bank")
    }

    // The mode is part of the draft too — switching it and leaving must prompt.
    @Test
    fun changingOnlyTheModeIsStillDirty() = runTest(dispatcher) {
        val model = PerAppViewModel(FakeSource(apps))
        dispatcher.scheduler.advanceUntilIdle()

        model.setMode(PerAppMode.AllowList)

        model.state.value.isDirty shouldBe true
    }

    // A disconnected save must not cost the user anything — but that guarantee
    // belongs to the service, not here. `reapplyPerApp` gates on
    // `ownTunnelActive()` and returns without touching the tunnel when nothing is
    // running, and `TunnelClient.reapplyPerApp` is a documented no-op when it is
    // not bound to one. Re-deciding it from the UI's view of the connection state
    // is what dropped the rebuild for a save landing mid-`Connecting` (see
    // aSaveWhileTheTunnelIsStartingStillRebuilds), so what is asserted now is that
    // the ViewModel always asks and never second-guesses.
    @Test
    fun savingWhileDisconnectedStillAsksTheServiceWhichNoOps() = runTest(dispatcher) {
        val source = FakeSource(apps)
        val model = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        model.toggle("com.example.bank")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        source.reapplyCount shouldBe 1
    }

    // The start sequence takes seconds (geo resolution, config validation), and a
    // save landing inside that window may be racing the read of the selection the
    // tunnel is about to be built with. Skipping the rebuild here is what leaves
    // the tunnel on the old selection with nothing to reconcile it.
    @Test
    fun aSaveWhileTheTunnelIsStartingStillRebuilds() = runTest(dispatcher) {
        val source = FakeSource(apps).apply { tunnelActive.value = true }
        val model = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        model.setMode(PerAppMode.DenyList)
        model.toggle("com.example.bank")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        source.reapplyCount shouldBe 1
    }

    // Once, at the explicit Save. Per-toggle would drop the tunnel repeatedly
    // through a multi-app edit (spec §7.3).
    @Test
    fun aMultiAppEditReconnectsExactlyOnce() = runTest(dispatcher) {
        val source = FakeSource(apps).apply { tunnelActive.value = true }
        val model = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        model.setMode(PerAppMode.DenyList)
        model.toggle("com.example.bank")
        model.toggle("com.example.maps")
        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        source.applyCount shouldBe 1
        source.reapplyCount shouldBe 1
    }

    // Nothing changed means nothing to reapply — a Save on a clean draft must not
    // cost the user their connection.
    @Test
    fun savingACleanDraftNeverReconnects() = runTest(dispatcher) {
        val source = FakeSource(apps).apply { tunnelActive.value = true }
        val model = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        model.save()
        dispatcher.scheduler.advanceUntilIdle()

        source.reapplyCount shouldBe 0
    }

    @Test
    fun anActiveTunnelIsReflectedInState() = runTest(dispatcher) {
        val source = FakeSource(apps).apply { tunnelActive.value = true }
        val model = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        model.state.value.isTunnelActive shouldBe true
    }

    // The round trip the picker performs: seed a draft from the store, write it
    // back. It must go through the RAW selection both ways. The store keeps a
    // list parked behind Off; the *effective* selection reports OFF for it, and a
    // picker seeded from that would show nothing ticked and then save the nothing
    // — two saves to lose both the list and the mode, always silently.
    @Test
    fun aSelectionSurvivesARoundTripThroughOff() = runTest(dispatcher) {
        val source = FakeSource(apps)

        val first = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()
        first.setMode(PerAppMode.DenyList)
        first.toggle("com.example.bank")
        first.save()
        dispatcher.scheduler.advanceUntilIdle()

        // Off, saved. The service now sees no packages at all...
        val second = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()
        second.setMode(PerAppMode.Off)
        second.save()
        dispatcher.scheduler.advanceUntilIdle()
        source.effective() shouldBe PerAppSelection.OFF

        // ...but the user's list is still theirs, and the picker still shows it.
        val third = PerAppViewModel(source)
        dispatcher.scheduler.advanceUntilIdle()

        third.state.value.rows.first { it.packageName == "com.example.bank" }.isSelected shouldBe true
        third.state.value.selectedCount shouldBe 1

        // And switching the mode back on restores exactly what was stored, rather
        // than committing an empty list that would collapse straight back to Off.
        third.setMode(PerAppMode.DenyList)
        third.save()
        dispatcher.scheduler.advanceUntilIdle()

        source.stored.value shouldBe PerAppSelection(PerAppMode.DenyList, setOf("com.example.bank"))
        source.effective() shouldBe PerAppSelection(PerAppMode.DenyList, setOf("com.example.bank"))
    }

    // A search filters the view, never the selection. Deriving either the count or
    // the empty-allow-list warning from the visible rows turns a query that
    // happens to match nothing into "you have selected no apps" plus a disabled
    // Save — a security warning about a state the user is not in.
    @Test
    fun aQueryThatMatchesNothingDoesNotEmptyTheAllowList() = runTest(dispatcher) {
        val model = PerAppViewModel(FakeSource(apps))
        dispatcher.scheduler.advanceUntilIdle()

        model.setMode(PerAppMode.AllowList)
        model.toggle("com.example.bank")
        model.search("zzzz")

        model.state.value.rows.shouldBeEmpty()
        model.state.value.selectedCount shouldBe 1
        model.state.value.isEmptyAllowList shouldBe false
    }

    // §7.2: selected first, each group still by label. Fixed at load — see
    // PerAppViewModel.selectedFirst for why it does not re-sort under a finger.
    @Test
    fun selectedRowsSortAheadOfUnselectedOnes() = runTest(dispatcher) {
        val stored = PerAppSelection(PerAppMode.DenyList, setOf("com.example.maps"))
        val model = PerAppViewModel(FakeSource(apps, stored))
        dispatcher.scheduler.advanceUntilIdle()

        model.state.value.rows.map { it.packageName } shouldBe
            listOf("com.example.maps", "com.example.bank")

        // Ticking does not reshuffle the list out from under the tap.
        model.toggle("com.example.bank")

        model.state.value.rows.map { it.packageName } shouldBe
            listOf("com.example.maps", "com.example.bank")
    }
}
