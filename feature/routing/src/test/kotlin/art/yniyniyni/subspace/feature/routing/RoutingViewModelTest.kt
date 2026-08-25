// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.GeoDownloadProgress
import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsState
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    // LongParameterList: one flow per RoutingSource axis, each defaulted so a
    // test names only the axis it is about. Bundling them would make every
    // test build a holder to change one field.
    @Suppress("LongParameterList")
    private class FakeSource(
        val sets: MutableStateFlow<List<StoredRuleSet>>,
        val activeId: MutableStateFlow<Long?> = MutableStateFlow(null),
        val installed: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        val failed: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
        val names: MutableStateFlow<Map<Long, String>> = MutableStateFlow(emptyMap()),
        val downloads: MutableStateFlow<Map<Long, GeoDownloadProgress>> = MutableStateFlow(emptyMap()),
        val pending: MutableStateFlow<RoutingImportOffer?> = MutableStateFlow(null),
    ) : RoutingSource {
        override val ruleSets = sets
        override val activeRuleSetId = activeId
        override val installedGeoFiles = installed
        override val failedGeoFiles = failed
        override val subscriptionNames = names
        override val downloadProgress = downloads
        var activated: Long? = null
        var cancelled: Long? = null

        override suspend fun setActive(id: Long?) {
            activated = id
            activeId.value = id
        }

        override suspend fun delete(id: Long) {
            sets.value = sets.value.filterNot { it.ruleSet.id == id }
        }

        override fun cancelDownload(id: Long) {
            cancelled = id
        }

        override val pendingOffer = pending

        override fun consumePendingOffer(offer: RoutingImportOffer) {
            if (pending.value == offer) pending.value = null
        }

        override suspend fun duplicate(
            id: Long,
            name: String,
        ): Long? {
            val original = sets.value.firstOrNull { it.ruleSet.id == id } ?: return null
            val copyId = sets.value.maxOf { it.ruleSet.id } + 1
            // Mirrors the real seam: a copy is a hand-made row (no provenance)
            // that keeps the original's rules.
            sets.value = sets.value + stored(original.ruleSet.copy(id = copyId, name = name))
            return copyId
        }

        /** What the row's *own* generation directory holds, distinct from the shared root. */
        val ownInstalled: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun installedGeoFilesFor(set: StoredRuleSet): Set<String> = ownInstalled.value

        override suspend fun ruleSet(id: Long): RoutingRuleSet? =
            sets.value.firstOrNull { it.ruleSet.id == id }?.ruleSet

        // RoutingViewModel never calls upsert — RoutingSource.upsert lost its default in fix
        // round 1 (Task 16, Minor: a silently-succeeding default is the wrong shape for a
        // write), so every RoutingSource implementer must say so explicitly now, even one that
        // never exercises this path.
        override suspend fun upsert(set: RoutingRuleSet): Long {
            val id = if (set.id == 0L) sets.value.maxOf { it.ruleSet.id } + 1 else set.id
            sets.value = sets.value.filterNot { it.ruleSet.id == id } + stored(set.copy(id = id))
            return id
        }
    }

    private companion object {
        /**
         * A hand-made set: no provenance, nothing downloaded, nothing failed.
         *
         * LongParameterList: these are StoredRuleSet's own axes, and every one
         * of them is defaulted. A test names only the axis it is about.
         */
        @Suppress("LongParameterList")
        fun stored(
            set: RoutingRuleSet,
            sourceKind: RoutingSourceKind? = null,
            subscriptionId: Long? = null,
            dns: ProfileDns? = null,
            assetState: RuleSetAssetState = RuleSetAssetState.None,
            assetFailure: RuleSetAssetFailure? = null,
        ) = StoredRuleSet(
            ruleSet = set,
            sourceKind = sourceKind,
            subscriptionId = subscriptionId,
            lastUpdated = null,
            fingerprint = null,
            geoIpUrl = null,
            geoSiteUrl = null,
            hasDns = dns != null,
            assetGeneration = 0L,
            assetState = assetState,
            assetFailure = assetFailure,
            dns = dns,
        )
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
        val source = FakeSource(MutableStateFlow(listOf(stored(literalSet))))

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.missingGeoFiles shouldBe emptySet()
        row.canActivate shouldBe true
    }

    @Test
    fun `a geo rule set is not activatable until its files are installed`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(geoSet))))

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.missingGeoFiles shouldBe setOf("geosite.dat")
        row.canActivate shouldBe false
    }

    @Test
    fun `installing the file makes it activatable`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(geoSet))))
        val viewModel = RoutingViewModel(source)

        source.installed.value = setOf("geosite.dat")

        viewModel.state.value.ruleSets.single().canActivate shouldBe true
    }

    // The gate must be enforced by the ViewModel, not only by a disabled button —
    // a disabled control is a hint, not a guarantee.
    @Test
    fun `activating a rule set with missing files is refused`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(geoSet))))

        RoutingViewModel(source).activate(geoSet.id)

        source.activated shouldBe null
        source.activeRuleSetId.value shouldBe null
    }

    @Test
    fun `activating an eligible rule set stores it`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(literalSet))))

        RoutingViewModel(source).activate(literalSet.id)

        source.activated shouldBe literalSet.id
    }

    @Test
    fun `routing can be turned off`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(literalSet))), MutableStateFlow(2L))

        RoutingViewModel(source).activate(null)

        source.activeRuleSetId.value shouldBe null
    }

    // Deleting the active set must clear the active id, or the tunnel resolves a
    // dangling reference on the next connect.
    @Test
    fun `deleting the active rule set turns routing off`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(literalSet))), MutableStateFlow(2L))

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
                sets = MutableStateFlow(listOf(stored(geoSet))),
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
    // Task 13 / spec §9. Without the badge, nothing on this screen tells the
    // user which rows their provider controls — the first question anyone
    // should be able to answer here.
    @Test
    fun `an imported profile is read-only and names its source`() = runTest {
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(stored(literalSet, RoutingSourceKind.Header, subscriptionId = 5L))),
                names = MutableStateFlow(mapOf(5L to "NameVPN")),
            )

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.isReadOnly shouldBe true
        row.sourceKind shouldBe RoutingSourceKind.Header
        row.subscriptionName shouldBe "NameVPN"
    }

    @Test
    fun `a hand-made set stays editable`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(literalSet))))

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.isReadOnly shouldBe false
        row.sourceKind shouldBe null
    }

    // A subscription whose group has no name yet must not render as From "null".
    @Test
    fun `a profile from an unnamed subscription shows no subscription name`() = runTest {
        val source =
            FakeSource(MutableStateFlow(listOf(stored(literalSet, RoutingSourceKind.Body, subscriptionId = 5L))))

        RoutingViewModel(source).state.value.ruleSets.single().subscriptionName shouldBe null
    }

    @Test
    fun `duplicating an imported profile yields an editable copy with no provenance`() = runTest {
        val source =
            FakeSource(MutableStateFlow(listOf(stored(literalSet, RoutingSourceKind.Qr))))
        val viewModel = RoutingViewModel(source)

        viewModel.duplicate(literalSet.id)

        val copy = viewModel.state.value.ruleSets.first { it.id != literalSet.id }
        copy.isReadOnly shouldBe false
        copy.sourceKind shouldBe null
        // name is uniquely indexed, so the copy cannot keep the original's
        copy.name shouldNotBe literalSet.name
    }

    // §A.3.1: the marker clears on success or deletion and on nothing else —
    // notably not on a fresh ViewModel, which is what a process death produces.
    @Test
    fun `a failed generation shows a marker that survives a reload`() = runTest {
        val source =
            FakeSource(
                MutableStateFlow(
                    listOf(
                        stored(
                            literalSet,
                            RoutingSourceKind.Deeplink,
                            assetState = RuleSetAssetState.Failed,
                            assetFailure = RuleSetAssetFailure.TimedOut,
                        ),
                    ),
                ),
            )

        RoutingViewModel(source).state.value.ruleSets.single().assetFailure shouldBe RuleSetAssetFailure.TimedOut
        RoutingViewModel(source).state.value.ruleSets.single().assetFailure shouldBe RuleSetAssetFailure.TimedOut
    }

    // RuleSetRow's own KDoc calls conflating these "the defect this screen
    // exists to avoid". M6 adds a third state and must not collapse any of them.
    @Test
    fun `pending is distinct from blocked and from failed`() = runTest {
        val source =
            FakeSource(
                MutableStateFlow(
                    listOf(stored(geoSet, RoutingSourceKind.Deeplink, assetState = RuleSetAssetState.Pending)),
                ),
            )

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.assetState shouldBe RuleSetAssetState.Pending
        row.assetFailure shouldBe null
        row.missingGeoFiles shouldBe setOf("geosite.dat")
    }

    @Test
    fun `a running download reports both counts`() = runTest {
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(stored(geoSet, RoutingSourceKind.Deeplink))),
                downloads = MutableStateFlow(mapOf(geoSet.id to GeoDownloadProgress("geosite.dat", 12L, 23L))),
            )

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.downloadProgress shouldBe GeoProgress(12L, 23L)
    }

    // A chunked response declares no length; "12 MB / 0 MB" would read as done.
    @Test
    fun `a download with no declared total keeps a null total`() = runTest {
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(stored(geoSet, RoutingSourceKind.Deeplink))),
                downloads = MutableStateFlow(mapOf(geoSet.id to GeoDownloadProgress("geosite.dat", 12L, null))),
            )

        RoutingViewModel(source).state.value.ruleSets.single().downloadProgress?.totalBytes shouldBe null
    }

    @Test
    fun `cancelling a download stops that rule set's generation`() = runTest {
        val source = FakeSource(MutableStateFlow(listOf(stored(geoSet, RoutingSourceKind.Deeplink))))

        RoutingViewModel(source).cancelDownload(geoSet.id)

        source.cancelled shouldBe geoSet.id
    }

    // /off and the routing-enable directive both need a visible target.
    @Test
    fun `a profile carrying valid DNS says it sets DNS`() = runTest {
        val dns = ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1"))
        val source =
            FakeSource(
                MutableStateFlow(listOf(stored(literalSet, RoutingSourceKind.Deeplink, dns = dns))),
            )

        RoutingViewModel(source).state.value.ruleSets.single().dnsState shouldBe DnsState.Applied
    }

    // Task 14. A deeplink reaches the sheet through this flow rather than a
    // navigation argument — the back stack is persisted and a base64 profile is
    // config material (§5.6).
    @Test
    fun `a delivered deeplink is offered for review`() = runTest {
        val offer = RoutingImportOffer.Deeplink("happ://routing/off")
        val source = FakeSource(MutableStateFlow(emptyList()), pending = MutableStateFlow(offer))

        RoutingViewModel(source).pendingOffer.value shouldBe offer
    }

    // The provider channels are the ones §A.1's threat model is written about,
    // and they carry the subscription that owns the row.
    @Test
    fun `a provider directive is offered with its subscription`() = runTest {
        val offer = RoutingImportOffer.Provider("happ://routing/add/x", subscriptionId = 5L)
        val source = FakeSource(MutableStateFlow(emptyList()), pending = MutableStateFlow(offer))

        RoutingViewModel(source).pendingOffer.value shouldBe offer
    }

    // A second offer delivered while the first was being handed over must not
    // be discarded unread.
    @Test
    fun `consuming clears only the offer that was taken`() = runTest {
        val first = RoutingImportOffer.Deeplink("happ://routing/off")
        val second = RoutingImportOffer.Deeplink("happ://routing/add/second")
        val source = FakeSource(MutableStateFlow(emptyList()), pending = MutableStateFlow(first))
        val viewModel = RoutingViewModel(source)

        source.pending.value = second
        viewModel.consumePendingOffer(first)

        source.pending.value shouldBe second
    }

    // Regression, P1: the list used to subtract every row's requirements from
    // the flat shared catalogue. A profile that owns a generation reads from
    // geo/sets/<id>/<gen>, so a same-named shared file made an unusable profile
    // look activatable, and an empty shared root blocked a valid one.
    @Test
    fun `an own-generation profile is gated on its own generation, not the shared root`() = runTest {
        val ownGeneration =
            stored(geoSet, RoutingSourceKind.Deeplink).copy(assetGeneration = 2)
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(ownGeneration)),
                // The shared root has the file; the row's own generation does not.
                installed = MutableStateFlow(setOf("geosite.dat")),
            )
        source.ownInstalled.value = emptySet()

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.missingGeoFiles shouldBe setOf("geosite.dat")
        row.canActivate shouldBe false
    }

    @Test
    fun `an own-generation profile with its files present activates despite an empty shared root`() = runTest {
        val ownGeneration =
            stored(geoSet, RoutingSourceKind.Deeplink).copy(assetGeneration = 2)
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(ownGeneration)),
                installed = MutableStateFlow(emptySet()),
            )
        source.ownInstalled.value = setOf("geosite.dat")

        val row = RoutingViewModel(source).state.value.ruleSets.single()

        row.missingGeoFiles shouldBe emptySet()
        row.canActivate shouldBe true
    }

    @Test
    fun `a dangling active id is not reported as off`() = runTest {
        val source =
            FakeSource(
                sets = MutableStateFlow(listOf(stored(literalSet))),
                activeId = MutableStateFlow(404L),
            )

        val state = RoutingViewModel(source).state.value

        state.activeRuleSetId shouldBe 404L
        state.ruleSets.single().isActive shouldBe false
    }
}
