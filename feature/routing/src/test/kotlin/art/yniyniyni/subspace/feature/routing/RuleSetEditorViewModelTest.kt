// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.EntryProblem
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import io.kotest.matchers.collections.shouldHaveSize
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
 * The brief's own `@Test` bodies (`addEntry`/`removeEntry`/`canSave`) are reproduced verbatim from
 * Task 16's brief. `viewModelScope` needs a Main dispatcher to run at all outside Android — the
 * same gap [RoutingViewModelTest]'s own file-level KDoc documents in full, quoting the exact
 * failure mode found when Task 15 shipped its brief's snippets without it (6 of 7 tests failed,
 * the 7th passed vacuously). That setup is added here too.
 *
 * The brief's own seven tests never call [RuleSetEditorViewModel.load] or
 * [RuleSetEditorViewModel.save] — every assertion in them follows from
 * [RuleSetEditorViewModel.addEntry]/[RuleSetEditorViewModel.removeEntry], which this
 * implementation deliberately keeps as plain, non-suspending `_state.update` calls (the same
 * shape [art.yniyniyni.subspace.feature.profiles.editor.EditorViewModel]'s own `onXChanged`
 * setters use), so **those seven pass even with `Dispatchers.setMain`/`resetMain` deleted** — there
 * is no coroutine in their path for a missing Main dispatcher to silently swallow. Proven by
 * running them with the `@Before`/`@After` below commented out: all seven still pass. That is not
 * the vacuous-pass failure mode Task 15 hit — those tests genuinely exercise synchronous code —
 * but it does mean this file's own `@Before`/`@After` cannot be verified against those seven
 * alone. The `save`/`load`/round-trip tests below this comment go through `viewModelScope.launch`
 * (mirroring [RoutingViewModel.activate]) specifically so this file has tests that put the
 * dispatcher setup to real use: with the `@Before`/`@After` deleted, `saving a named rule set
 * writes the current draft through upsert`, `loading an existing rule set populates its working
 * copy` and `loading a rule set with no matching row starts a fresh draft at that id` all fail —
 * confirmed by deleting the setup and re-running (see the task report for the exact output).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleSetEditorViewModelTest {
    private class FakeSource(
        val sets: MutableStateFlow<List<RoutingRuleSet>> = MutableStateFlow(emptyList()),
    ) : RoutingSource {
        override val ruleSets = sets
        override val activeRuleSetId = MutableStateFlow<Long?>(null)
        override val installedGeoFiles = MutableStateFlow(emptySet<String>())
        var upserted: RoutingRuleSet? = null

        override suspend fun setActive(id: Long?) = Unit

        override suspend fun delete(id: Long) = Unit

        override suspend fun ruleSet(id: Long): RoutingRuleSet? = sets.value.firstOrNull { it.id == id }

        override suspend fun upsert(set: RoutingRuleSet): Long {
            upserted = set
            val without = sets.value.filterNot { it.id == set.id }
            val stored = if (set.id == 0L) set.copy(id = 1L) else set
            sets.value = without + stored
            return stored.id
        }
    }

    private fun editor(source: RoutingSource = FakeSource()): RuleSetEditorViewModel = RuleSetEditorViewModel(source)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a valid entry is added to its bucket`() = runTest {
        val viewModel = editor()

        viewModel.addEntry(RouteOutcome.BLOCK, BucketField.SITES, "geosite:category-ads-all")

        viewModel.state.value.bucket(RouteOutcome.BLOCK, BucketField.SITES) shouldBe
            listOf("geosite:category-ads-all")
    }

    @Test
    fun `an invalid entry is rejected with a reason and not added`() = runTest {
        val viewModel = editor()

        viewModel.addEntry(RouteOutcome.DIRECT, BucketField.IPS, "999.1.1.1")

        viewModel.state.value.entryProblem shouldBe EntryProblem.MalformedAddress
        viewModel.state.value.bucket(RouteOutcome.DIRECT, BucketField.IPS) shouldBe emptyList()
    }

    @Test
    fun `the problem clears on the next valid entry`() = runTest {
        val viewModel = editor()
        viewModel.addEntry(RouteOutcome.DIRECT, BucketField.IPS, "999.1.1.1")

        viewModel.addEntry(RouteOutcome.DIRECT, BucketField.IPS, "10.0.0.0/8")

        viewModel.state.value.entryProblem shouldBe null
    }

    // The generator interpolates entries into hand-written JSON; a quote would
    // inject arbitrary config. The editor is the guard (Part 1 Task 2).
    @Test
    fun `a json-breaking entry is rejected`() = runTest {
        val viewModel = editor()

        viewModel.addEntry(RouteOutcome.PROXY, BucketField.SITES, """ex"ample.com""")

        viewModel.state.value.entryProblem shouldBe EntryProblem.IllegalCharacter
    }

    @Test
    fun `a duplicate entry is not added twice`() = runTest {
        val viewModel = editor()
        viewModel.addEntry(RouteOutcome.BLOCK, BucketField.SITES, "example.com")

        viewModel.addEntry(RouteOutcome.BLOCK, BucketField.SITES, "example.com")

        viewModel.state.value.bucket(RouteOutcome.BLOCK, BucketField.SITES) shouldHaveSize 1
    }

    @Test
    fun `removing an entry leaves the rest in order`() = runTest {
        val viewModel = editor()
        listOf("a.example", "b.example", "c.example").forEach {
            viewModel.addEntry(RouteOutcome.PROXY, BucketField.SITES, it)
        }

        viewModel.removeEntry(RouteOutcome.PROXY, BucketField.SITES, "b.example")

        viewModel.state.value.bucket(RouteOutcome.PROXY, BucketField.SITES) shouldBe
            listOf("a.example", "c.example")
    }

    @Test
    fun `an unnamed rule set cannot be saved`() = runTest {
        val viewModel = editor()

        viewModel.state.value.canSave shouldBe false
    }

    // Everything below is additional coverage beyond the brief's own seven tests, for the
    // behaviours the task description calls out explicitly: the six buckets round-tripping,
    // save, the order permutation, and domain strategy selection.

    @Test
    fun `a name makes the draft savable`() = runTest {
        val viewModel = editor()

        viewModel.setName("ads")

        viewModel.state.value.canSave shouldBe true
    }

    @Test
    fun `saving a named rule set writes the current draft through upsert`() = runTest {
        val source = FakeSource()
        val viewModel = editor(source)
        viewModel.setName("ads")
        viewModel.addEntry(RouteOutcome.BLOCK, BucketField.SITES, "geosite:category-ads-all")
        viewModel.addEntry(RouteOutcome.DIRECT, BucketField.IPS, "10.0.0.0/8")

        viewModel.save()

        val written = source.upserted
        written?.name shouldBe "ads"
        written?.bucket(RouteOutcome.BLOCK)?.sites shouldBe listOf("geosite:category-ads-all")
        written?.bucket(RouteOutcome.DIRECT)?.ips shouldBe listOf("10.0.0.0/8")
        viewModel.state.value.saved shouldBe true
    }

    // The gate must be enforced by the ViewModel, not only by a disabled Save button — the same
    // "a disabled control is a hint, not a guarantee" reasoning RoutingViewModelTest documents for
    // its own activation gate.
    @Test
    fun `saving an unnamed rule set does not write through`() = runTest {
        val source = FakeSource()
        val viewModel = editor(source)

        viewModel.save()

        source.upserted shouldBe null
        viewModel.state.value.saved shouldBe false
    }

    @Test
    fun `loading an existing rule set populates its working copy`() = runTest {
        val stored =
            RoutingRuleSet(
                id = 7,
                name = "lan",
                buckets =
                mapOf(
                    RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8")),
                    RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
                ),
                order = listOf(RouteOutcome.DIRECT, RouteOutcome.BLOCK, RouteOutcome.PROXY),
                domainStrategy = DomainStrategy.AS_IS,
            )
        val viewModel = editor(FakeSource(MutableStateFlow(listOf(stored))))

        viewModel.load(7)

        val state = viewModel.state.value
        state.name shouldBe "lan"
        state.bucket(RouteOutcome.DIRECT, BucketField.IPS) shouldBe listOf("10.0.0.0/8")
        state.bucket(RouteOutcome.BLOCK, BucketField.SITES) shouldBe listOf("geosite:category-ads-all")
        state.order shouldBe listOf(RouteOutcome.DIRECT, RouteOutcome.BLOCK, RouteOutcome.PROXY)
        state.domainStrategy shouldBe DomainStrategy.AS_IS
        state.loading shouldBe false
    }

    // NEW_RULE_SET (`:app`'s sentinel, 0L) resolves no row — the same value RoutingRuleSet's own
    // `id` already defaults to — so this is also the "create new" path, not only a stale-id edge
    // case.
    @Test
    fun `loading a rule set with no matching row starts a fresh draft at that id`() = runTest {
        val viewModel = editor(FakeSource())

        viewModel.load(0L)

        val state = viewModel.state.value
        state.id shouldBe 0L
        state.name shouldBe ""
        state.loading shouldBe false
        state.canSave shouldBe false
    }

    @Test
    fun `a valid order permutation is accepted`() = runTest {
        val viewModel = editor()
        val reordered = listOf(RouteOutcome.PROXY, RouteOutcome.DIRECT, RouteOutcome.BLOCK)

        viewModel.setOrder(reordered)

        viewModel.state.value.order shouldBe reordered
    }

    // RoutingRuleSet's own init requires a permutation of every RouteOutcome — this is the guard
    // that keeps save() from ever building one that would throw constructing the draft.
    @Test
    fun `an incomplete order is not accepted`() = runTest {
        val viewModel = editor()
        val original = viewModel.state.value.order

        viewModel.setOrder(listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY))

        viewModel.state.value.order shouldBe original
    }

    @Test
    fun `domain strategy selection is stored`() = runTest {
        val viewModel = editor()

        viewModel.setDomainStrategy(DomainStrategy.IP_ON_DEMAND)

        viewModel.state.value.domainStrategy shouldBe DomainStrategy.IP_ON_DEMAND
    }

    @Test
    fun `all six buckets round-trip independently`() = runTest {
        val viewModel = editor()
        val expected =
            mapOf(
                (RouteOutcome.BLOCK to BucketField.SITES) to "block-site.example",
                (RouteOutcome.BLOCK to BucketField.IPS) to "10.1.0.0/16",
                (RouteOutcome.PROXY to BucketField.SITES) to "proxy-site.example",
                (RouteOutcome.PROXY to BucketField.IPS) to "10.2.0.0/16",
                (RouteOutcome.DIRECT to BucketField.SITES) to "direct-site.example",
                (RouteOutcome.DIRECT to BucketField.IPS) to "10.3.0.0/16",
            )

        expected.forEach { (bucketKey, entry) -> viewModel.addEntry(bucketKey.first, bucketKey.second, entry) }

        expected.forEach { (bucketKey, entry) ->
            viewModel.state.value.bucket(bucketKey.first, bucketKey.second) shouldBe listOf(entry)
        }
    }
}
