// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Task 15's brief specifies every `@Test` body and [FakeSource] below verbatim; both are
 * reproduced unmodified. `viewModelScope` needs a Main dispatcher to run at all outside Android
 * — the same gap [art.yniyniyni.subspace.feature.settings.SettingsViewModelTest] and
 * [art.yniyniyni.subspace.feature.home.HomeViewModelTest] each document and close with an
 * identical [UnconfinedTestDispatcher] — so that setup (absent from the brief's own snippet, and
 * not part of what it called out as verbatim) is added here too: without it every `@Test` below
 * fails, not because [RoutingViewModel] is wrong, but because nothing schedules its `init` block
 * or its `activate`/`delete` coroutines at all. Confirmed by running the brief's snippet
 * unmodified first: 6 of 7 failed with stale/absent state, none with a dispatcher-missing
 * exception — consistent with `viewModelScope`'s coroutines silently never running rather than
 * crashing, since `viewModelScope` is its own [kotlinx.coroutines.CoroutineScope], not a child of
 * `runTest`'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingViewModelTest {
    private val geoSet =
        RoutingRuleSet(
            id = 1,
            name = "ads",
            buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all"))),
        )
    private val literalSet =
        RoutingRuleSet(
            id = 2,
            name = "lan",
            buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8"))),
        )

    private class FakeSource(
        val sets: MutableStateFlow<List<RoutingRuleSet>>,
        val activeId: MutableStateFlow<Long?> = MutableStateFlow(null),
        val installed: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        val failed: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
    ) : RoutingSource {
        override val ruleSets = sets
        override val activeRuleSetId = activeId
        override val installedGeoFiles = installed
        override val failedGeoFiles = failed
        var activated: Long? = null

        override suspend fun setActive(id: Long?) {
            activated = id
            activeId.value = id
        }

        override suspend fun delete(id: Long) {
            sets.value = sets.value.filterNot { it.id == id }
        }

        // RoutingViewModel never calls upsert — RoutingSource.upsert lost its default in fix
        // round 1 (Task 16, Minor: a silently-succeeding default is the wrong shape for a
        // write), so every RoutingSource implementer must say so explicitly now, even one that
        // never exercises this path.
        override suspend fun upsert(set: RoutingRuleSet): Long = error("upsert not exercised by RoutingViewModelTest")
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
    fun `a literal-only rule set is activatable with nothing installed`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(literalSet)))

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.missingGeoFiles shouldBe emptySet()
        row.canActivate shouldBe true
    }

    @Test
    fun `a geo rule set is not activatable until its files are installed`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(geoSet)))

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.missingGeoFiles shouldBe setOf("geosite.dat")
        row.canActivate shouldBe false
    }

    @Test
    fun `installing the file makes it activatable`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(geoSet)))
        val viewModel = RoutingViewModel(source)

        source.installed.value = setOf("geosite.dat")

        viewModel.state.value.ruleSets.single().canActivate shouldBe true
    }

    // The gate must be enforced by the ViewModel, not only by a disabled button —
    // a disabled control is a hint, not a guarantee.
    @Test
    fun `activating a rule set with missing files is refused`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(geoSet)))

        RoutingViewModel(source).activate(geoSet.id)

        source.activated shouldBe null
        source.activeRuleSetId.value shouldBe null
    }

    @Test
    fun `activating an eligible rule set stores it`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(literalSet)))

        RoutingViewModel(source).activate(literalSet.id)

        source.activated shouldBe literalSet.id
    }

    @Test
    fun `routing can be turned off`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(literalSet)), MutableStateFlow(2L))

        RoutingViewModel(source).activate(null)

        source.activeRuleSetId.value shouldBe null
    }

    // Deleting the active set must clear the active id, or the tunnel resolves a
    // dangling reference on the next connect.
    @Test
    fun `deleting the active rule set turns routing off`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(literalSet)), MutableStateFlow(2L))

        RoutingViewModel(source).delete(literalSet.id)

        source.activeRuleSetId.value shouldBe null
    }

    /**
     * The two markers are independent, and this is the half that is easy to get
     * backwards: a failed *refresh* of a file that is still installed and still
     * working must not block activation. Treating it as blocking would strand a
     * user on a working rule set because a CDN was briefly unreachable.
     *
     * `RoutingListScreenContentTest` asserts the rendered selector stays
     * enabled; this pins the rule itself, and unlike that one it can actually
     * run without a device.
     */
    @Test
    fun `a failed geo update does not block activation`() = runTest {
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(geoSet)),
                installed = MutableStateFlow(setOf("geosite.dat")),
                failed = MutableStateFlow(setOf("geosite.dat")),
            )

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.hasFailedGeoUpdate shouldBe true
        row.missingGeoFiles shouldBe emptySet()
        row.canActivate shouldBe true
    }

    /**
     * Off-ness comes from the stored id, not from "no row is active".
     *
     * A stored id whose row has been deleted elsewhere leaves the list with no
     * active row while the setting still holds a value; re-deriving would show
     * "Off" and quietly disagree with what is stored.
     */
    @Test
    fun `a dangling active id is not reported as off`() = runTest {
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(literalSet)),
                activeId = MutableStateFlow(404L),
            )

        val state = RoutingViewModel(source).state.value

        state.activeRuleSetId shouldBe 404L
        state.ruleSets.single().isActive shouldBe false
    }
}
