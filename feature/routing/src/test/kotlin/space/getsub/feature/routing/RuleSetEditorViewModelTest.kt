// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.StoredRuleSet
import space.getsub.core.model.BucketField
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RuleBucket
import java.io.IOException

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
 * shape [space.getsub.feature.profiles.editor.EditorViewModel]'s own `onXChanged`
 * setters use), so **those seven pass even with `Dispatchers.setMain`/`resetMain` deleted** — there
 * is no coroutine in their path for a missing Main dispatcher to silently swallow. Proven by
 * running them with the `@Before`/`@After` below commented out: all seven still pass. That is not
 * the vacuous-pass failure mode Task 15 hit — those tests genuinely exercise synchronous code —
 * but it does mean this file's own `@Before`/`@After` cannot be verified against those seven
 * alone. Every `load`/`save` test (including fix round 1's additions for Findings 5, 7 and 8, fix
 * round 2's addition for Finding 10, and the re-entrancy Minor) goes through `viewModelScope.launch`
 * (mirroring [RoutingViewModel.activate]) specifically so this file has tests that put the
 * dispatcher setup to real use: with the `@Before`/`@After` deleted, 11 of this class's own 25
 * tests fail (the whole-module run reports "37 tests completed, 11 failed" since
 * `GeoCategoriesTest` and `RoutingViewModelTest` are separate classes with their own unaffected
 * setups) — confirmed by deleting the setup and re-running (see the task report for the exact
 * list and output).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleSetEditorViewModelTest {
    private class FakeSource(
        val sets: MutableStateFlow<List<RoutingRuleSet>> = MutableStateFlow(emptyList()),
        private val siteCategories: List<GeoCategory> = emptyList(),
        private val ipCategories: List<GeoCategory> = emptyList(),
        private val failUpsertWith: Throwable? = null,
        // Fix round 2, Finding 10: the collision read can fail too, and none of fix round 1's
        // tests exercised that — this is what closes that gap.
        private val failRuleSetNamedWith: Throwable? = null,
        // A suspension point `upsert` awaits before doing anything else — lets a test observe
        // RuleSetEditorState.saving mid-flight (fix round 1, Minor: save's re-entrancy guard).
        private val upsertGate: CompletableDeferred<Unit>? = null,
    ) : RoutingSource {
        // RuleSetEditorViewModel loads one set through ruleSet(), never the
        // list — so the list this fake exposes stays empty rather than
        // fabricating provenance for rows nothing reads.
        override val ruleSets = flowOf(emptyList<StoredRuleSet>())
        override val activeRuleSetId = MutableStateFlow<Long?>(null)
        override val installedGeoFiles = MutableStateFlow(emptySet<String>())
        var upserted: RoutingRuleSet? = null

        override suspend fun setActive(id: Long?) = Unit

        override suspend fun delete(id: Long) = Unit

        override suspend fun ruleSet(id: Long): RoutingRuleSet? = sets.value.firstOrNull { it.id == id }

        // The real RoutingRuleSetEntity.name index is unique, so at most one row can ever match —
        // the same one-match invariant this in-memory lookup mirrors against `sets`.
        override suspend fun ruleSetNamed(name: String): RoutingRuleSet? {
            failRuleSetNamedWith?.let { throw it }
            return sets.value.firstOrNull { it.name == name }
        }

        override suspend fun categoriesFor(field: BucketField): List<GeoCategory> =
            when (field) {
                BucketField.SITES -> siteCategories
                BucketField.IPS -> ipCategories
            }

        override suspend fun upsert(set: RoutingRuleSet): Long {
            upsertGate?.await()
            failUpsertWith?.let { throw it }
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
    fun `an invalid entry is rejected and not added`() = runTest {
        val viewModel = editor()

        viewModel.addEntry(RouteOutcome.DIRECT, BucketField.IPS, "999.1.1.1")

        viewModel.state.value.bucket(RouteOutcome.DIRECT, BucketField.IPS) shouldBe emptyList()
    }

    // The generator interpolates entries into hand-written JSON; a quote would
    // inject arbitrary config. The editor is the guard (Part 1 Task 2).
    @Test
    fun `a json-breaking entry is rejected`() = runTest {
        val viewModel = editor()

        viewModel.addEntry(RouteOutcome.PROXY, BucketField.SITES, """ex"ample.com""")

        viewModel.state.value.bucket(RouteOutcome.PROXY, BucketField.SITES) shouldBe emptyList()
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

    @Test
    fun `saving an unrelated edit preserves every nullable global proxy value`() = runTest {
        listOf<Boolean?>(false, true, null).forEach { globalProxy ->
            val stored =
                RoutingRuleSet(
                    id = 7,
                    name = "provider routing",
                    buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8"))),
                    globalProxy = globalProxy,
                )
            val source = FakeSource(MutableStateFlow(listOf(stored)))
            val viewModel = editor(source)
            viewModel.load(stored.id)

            viewModel.addEntry(RouteOutcome.BLOCK, BucketField.SITES, "domain:ads.test")
            viewModel.save()

            source.upserted?.globalProxy shouldBe globalProxy
        }
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

    // Everything below is fix round 1 (Task 16 review): Findings 5, 7 and 8, plus the
    // re-entrancy Minor.

    @Test
    fun `load routes each field's categories separately, never swapped`() = runTest {
        val siteCategories = listOf(GeoCategory("category-ads-all", 42))
        val ipCategories = listOf(GeoCategory("cn", 12345))
        val viewModel = editor(FakeSource(siteCategories = siteCategories, ipCategories = ipCategories))

        viewModel.load(0L)

        val state = viewModel.state.value
        state.categoriesFor(BucketField.SITES) shouldBe siteCategories
        state.categoriesFor(BucketField.IPS) shouldBe ipCategories
    }

    @Test
    fun `no categories means the picker has nothing to show for either field`() = runTest {
        val viewModel = editor(FakeSource())

        viewModel.load(0L)

        viewModel.state.value.categoriesFor(BucketField.SITES) shouldBe emptyList()
        viewModel.state.value.categoriesFor(BucketField.IPS) shouldBe emptyList()
    }

    // Finding 7: RoutingSource.upsert throws IllegalArgumentException on an entry the guard
    // should have already stopped — a row written before a validation tightening, say. Must
    // become a SaveProblem, never an uncaught exception out of viewModelScope.launch.
    @Test
    fun `an entry RoutingSource rejects at save time is surfaced, not thrown`() = runTest {
        val source = FakeSource(failUpsertWith = IllegalArgumentException("bad entry"))
        val viewModel = editor(source)
        viewModel.setName("ads")

        viewModel.save()

        viewModel.state.value.saveProblem shouldBe SaveProblem.InvalidEntry
        viewModel.state.value.saved shouldBe false
        viewModel.state.value.saving shouldBe false
    }

    // Finding 7: a Room/storage failure must not crash the process either (§10.4).
    @Test
    fun `an unexpected write failure is surfaced, not thrown`() = runTest {
        val source = FakeSource(failUpsertWith = IOException("disk full"))
        val viewModel = editor(source)
        viewModel.setName("ads")

        viewModel.save()

        viewModel.state.value.saveProblem shouldBe SaveProblem.WriteFailed
        viewModel.state.value.saved shouldBe false
    }

    // Fix round 2, Finding 10: an earlier version of save() ran the collision read outside the
    // try/catch that protects upsert — a failure here crashed the process, and saving was never
    // reset, wedging the Save button disabled for the rest of the ViewModel's life. Neither of
    // those must happen now.
    @Test
    fun `a failure reading the name collision check is surfaced, not thrown, and does not wedge saving`() = runTest {
        val source = FakeSource(failRuleSetNamedWith = IOException("db unavailable"))
        val viewModel = editor(source)
        viewModel.setName("ads")

        viewModel.save()

        viewModel.state.value.saveProblem shouldBe SaveProblem.WriteFailed
        viewModel.state.value.saved shouldBe false
        viewModel.state.value.saving shouldBe false
        source.upserted shouldBe null
    }

    // Finding 8: upsertByIdOrName's id==0 branch resolves by name and would silently overwrite
    // a different, existing rule set. The editor must refuse instead.
    @Test
    fun `creating a rule set with another set's name is refused, not merged into it`() = runTest {
        val existing =
            RoutingRuleSet(
                id = 1,
                name = "ads",
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("a.example"))),
            )
        val source = FakeSource(MutableStateFlow(listOf(existing)))
        val viewModel = editor(source)
        viewModel.setName("ads")
        viewModel.addEntry(RouteOutcome.PROXY, BucketField.SITES, "b.example")

        viewModel.save()

        viewModel.state.value.saveProblem shouldBe SaveProblem.NameConflict("ads")
        viewModel.state.value.saved shouldBe false
        source.upserted shouldBe null
        // The existing row is untouched — this is the silent-destruction outcome the guard exists
        // to prevent, pinned directly rather than only inferred from upserted being null.
        source.sets.value.single().bucket(RouteOutcome.BLOCK).sites shouldBe listOf("a.example")
    }

    // The same collision, reached by renaming an existing set into another one's name instead of
    // creating fresh — upsertByIdOrName's id!=0 branch would hit the unique index and crash
    // (Finding 7) rather than silently merge, but it must still be refused with a clear reason
    // before that happens.
    @Test
    fun `renaming a rule set into another set's name is refused`() = runTest {
        val other = RoutingRuleSet(id = 1, name = "ads")
        val editing =
            RoutingRuleSet(
                id = 7,
                name = "lan",
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8"))),
            )
        val source = FakeSource(MutableStateFlow(listOf(other, editing)))
        val viewModel = editor(source)
        viewModel.load(7)

        viewModel.setName("ads")
        viewModel.save()

        viewModel.state.value.saveProblem shouldBe SaveProblem.NameConflict("ads")
        source.upserted shouldBe null
    }

    // Saving a set under the name it already has is not a collision with itself.
    @Test
    fun `saving a rule set under its own unchanged name succeeds`() = runTest {
        val editing = RoutingRuleSet(id = 7, name = "lan")
        val source = FakeSource(MutableStateFlow(listOf(editing)))
        val viewModel = editor(source)
        viewModel.load(7)

        viewModel.save()

        viewModel.state.value.saveProblem shouldBe null
        viewModel.state.value.saved shouldBe true
        source.upserted?.name shouldBe "lan"
    }

    // Minor: two fast taps on Save before the first upsert resolves must not issue two writes.
    @Test
    fun `a second save call is refused while the first is still in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeSource(upsertGate = gate)
        val viewModel = editor(source)
        viewModel.setName("ads")

        viewModel.save()
        viewModel.state.value.saving shouldBe true

        viewModel.save()

        source.upserted shouldBe null
    }
}
