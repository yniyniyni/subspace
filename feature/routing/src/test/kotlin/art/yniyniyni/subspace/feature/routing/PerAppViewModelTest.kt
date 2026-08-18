// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.InstalledApp
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
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

    private class FakeSource(
        private val installedApps: List<InstalledApp>,
        initial: PerAppSelection = PerAppSelection.OFF,
    ) : PerAppSource {
        val stored = MutableStateFlow(initial)
        var applyCount = 0

        override val selection = stored

        override suspend fun installed(): List<InstalledApp> = installedApps

        override suspend fun apply(mode: PerAppMode, packages: Set<String>) {
            applyCount++
            stored.value = PerAppSelection(mode, packages)
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
}
