// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.GeoFilePreview
import art.yniyniyni.subspace.core.data.ImportOutcome
import art.yniyniyni.subspace.core.data.ImportPreview
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.data.serialization.ProfileDnsCodec
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsState
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import art.yniyniyni.subspace.core.model.requiredGeoFiles
import art.yniyniyni.subspace.core.parser.routing.ImportProblem
import art.yniyniyni.subspace.core.parser.routing.RoutingVerb
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64

/**
 * Task 12: every routing-profile import stops at the review sheet until the
 * user confirms. Collaborators are in-memory fakes of the preview/apply
 * contract; parse is the real [art.yniyniyni.subspace.core.parser.routing.RoutingProfileImport].
 *
 * `viewModelScope` needs a Main dispatcher outside Android — same gap
 * [RoutingViewModelTest] documents and closes with [UnconfinedTestDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportReviewViewModelTest {
    private val downloads = mutableListOf<String>()
    private lateinit var repository: FakeRoutingRepository
    private lateinit var settings: FakeSettingsRepository
    private lateinit var viewModel: ImportReviewViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        downloads.clear()
        repository = FakeRoutingRepository()
        settings = FakeSettingsRepository()
        viewModel =
            ImportReviewViewModel(
                FakeImportReviewSource(repository, settings, downloads),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aProfileThatReplacesAnExistingOneSaysSo() = runTest {
        repository.upsertProfile(sampleProfile(), RoutingSourceKind.Deeplink, null)
        viewModel.offer(
            linkFor(sampleProfile().copy(lastUpdated = 1_800_000_000L, buckets = emptyMap())),
            RoutingSourceKind.Deeplink,
        )
        viewModel.state.value.replacesExisting shouldBe true
    }

    @Test
    fun globalProxyFalseIsRenderedAsADefaultRouteNotACount() = runTest {
        viewModel.offer(linkFor(sampleProfile().copy(globalProxy = false)), RoutingSourceKind.Deeplink)
        viewModel.state.value.defaultRouteIsDirect shouldBe true
    }

    @Test
    fun aValidDnsBlockIsShownAsApplied() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.state.value.dnsState shouldBe DnsState.Applied
        viewModel.state.value.dns shouldBe sampleProfile().dns
    }

    @Test
    fun anInvalidDnsBlockIsShownAsInvalid() = runTest {
        viewModel.offer(invalidDnsLink(), RoutingSourceKind.Deeplink)

        viewModel.state.value.dnsState shouldBe DnsState.Invalid
        viewModel.state.value.dns shouldBe ProfileDns.INVALID
    }

    @Test
    fun geoHostsAreShownBeforeAnythingIsFetched() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.state.value.geoDownloads.map { it.host } shouldBe listOf("example.test", "example.test")
        downloads.shouldBeEmpty()
    }

    @Test
    fun nothingIsStoredUntilConfirmIsCalled() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        repository.observeAllStored().first().shouldBeEmpty()
        viewModel.confirm()
        repository.observeAllStored().first().size shouldBe 1
    }

    @Test
    fun dismissingStoresNothing() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.dismiss()
        repository.observeAllStored().first().shouldBeEmpty()
    }

    @Test
    fun anUnchangedProfileNeverReachesTheSheet() = runTest {
        val link = linkFor(sampleProfile())
        viewModel.offer(link, RoutingSourceKind.Deeplink)
        viewModel.confirm()

        viewModel.offer(link, RoutingSourceKind.Deeplink)
        // Spec §7.3: silent. A sheet the user sees hourly is a sheet they stop reading.
        viewModel.state.value.stage shouldBe Stage.Done
    }

    @Test
    fun aMalformedLinkNamesTheProblemRatherThanFailingSilently() = runTest {
        viewModel.offer("happ://routing/add/!!!", RoutingSourceKind.Deeplink)
        viewModel.state.value.stage shouldBe Stage.Rejected
        viewModel.state.value.problem shouldBe ImportProblem.MalformedBase64
    }

    @Test
    fun offIsConfirmedTooBecauseItDisablesTheUsersRules() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.confirm()
        viewModel.offer("happ://routing/off", RoutingSourceKind.Deeplink)
        viewModel.state.value.stage shouldBe Stage.Reviewing
        settings.activeRoutingRuleSetId.first() shouldNotBe null
        viewModel.confirm()
        settings.activeRoutingRuleSetId.first() shouldBe null
    }

    @Test
    fun aThrownApplyLeavesTheSheetActionable() = runTest {
        viewModel =
            ImportReviewViewModel(
                FakeImportReviewSource(
                    repository,
                    settings,
                    downloads,
                    failApplyWith = IOException("disk full"),
                ),
            )
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.confirm()

        viewModel.state.value.stage shouldBe Stage.Reviewing
        repository.observeAllStored().first().shouldBeEmpty()
        viewModel.dismiss()
        viewModel.state.value.stage shouldBe Stage.Done
    }

    @Test
    fun aThrownDisableLeavesTheSheetActionable() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.confirm()
        viewModel =
            ImportReviewViewModel(
                FakeImportReviewSource(
                    repository,
                    settings,
                    downloads,
                    failDisableWith = IOException("db unavailable"),
                ),
            )
        viewModel.offer("happ://routing/off", RoutingSourceKind.Deeplink)
        viewModel.confirm()

        viewModel.state.value.stage shouldBe Stage.Reviewing
        settings.activeRoutingRuleSetId.first() shouldNotBe null
    }

    @Test
    fun theChannelThatOfferedTheProfileIsTheChannelThatIsRecorded() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Clipboard)
        viewModel.confirm()

        repository.observeAllStored().first().single().sourceKind shouldBe RoutingSourceKind.Clipboard
    }

    @Test
    fun aProviderDeliveredProfileRecordsItsSubscription() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Header, subscriptionId = 7L)
        viewModel.confirm()

        val stored = repository.observeAllStored().first().single()
        stored.sourceKind shouldBe RoutingSourceKind.Header
        stored.subscriptionId shouldBe 7L
    }

    @Test
    fun offIsStatedAsADisableRatherThanInferredFromAnAbsentName() = runTest {
        viewModel.offer("happ://routing/off", RoutingSourceKind.Deeplink)

        viewModel.state.value.isDisableRouting shouldBe true
    }

    @Test
    fun aProfileImportIsNeverMistakenForADisable() = runTest {
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)

        viewModel.state.value.isDisableRouting shouldBe false
    }

    // Regression, P1: a provider directive stays in the database, so the caller
    // records that it was shown. Acknowledging before parse/preview finished
    // meant a failed preview filtered the directive out forever.
    @Test
    fun aPreviewFailureIsNotAcknowledged() = runTest {
        viewModel =
            ImportReviewViewModel(
                FakeImportReviewSource(
                    repository,
                    settings,
                    downloads,
                    failPreviewWith = IOException("database unavailable"),
                ),
            )

        val accepted = viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Header, subscriptionId = 7L)

        accepted shouldBe false
        viewModel.state.value.stage shouldNotBe Stage.Reviewing
    }

    @Test
    fun aHandoffTheSheetTookIsAcknowledged() = runTest {
        val accepted = viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Header, subscriptionId = 7L)

        accepted shouldBe true
        viewModel.state.value.stage shouldBe Stage.Reviewing
    }

    // A malformed link is still a handoff: the sheet owns it and shows why.
    @Test
    fun aRejectedLinkIsStillAcknowledged() = runTest {
        viewModel.offer("happ://routing/add/!!!", RoutingSourceKind.Header) shouldBe true
    }

    // Regression, P2: timeouts, rejected files and failed installs are returned,
    // not thrown. Treating every normal return as success closed the sheet
    // exactly as it does on success, with no reason and no retry.
    @Test
    fun aFailedImportStaysVisibleWithItsReason() = runTest {
        viewModel =
            ImportReviewViewModel(
                FakeImportReviewSource(
                    repository,
                    settings,
                    downloads,
                    applyOutcome = ImportOutcome.Failed(1L, RuleSetAssetFailure.TimedOut),
                ),
            )
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)

        viewModel.confirm()

        viewModel.state.value.stage shouldBe Stage.Failed
        viewModel.state.value.failure shouldBe RuleSetAssetFailure.TimedOut
    }

    @Test
    fun aFailedImportCanBeRetried() = runTest {
        viewModel =
            ImportReviewViewModel(
                FakeImportReviewSource(
                    repository,
                    settings,
                    downloads,
                    applyOutcome = ImportOutcome.Failed(1L, RuleSetAssetFailure.DownloadFailed),
                ),
            )
        viewModel.offer(linkFor(sampleProfile()), RoutingSourceKind.Deeplink)
        viewModel.confirm()

        viewModel.retry()

        viewModel.state.value.stage shouldBe Stage.Reviewing
        viewModel.state.value.failure shouldBe null
    }

    private fun sampleProfile(): RoutingProfile {
        val buckets =
            mapOf(
                RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:cn"), ips = listOf("geoip:cn")),
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8")),
            )
        return RoutingProfile(
            name = "RussiaInside",
            globalProxy = false,
            routeOrder = listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT),
            domainStrategy = DomainStrategy.IP_IF_NON_MATCH,
            buckets = buckets,
            geoIpUrl = "https://example.test/geoip.dat",
            geoSiteUrl = "https://example.test/geosite.dat",
            lastUpdated = 1_700_000_000L,
            dns = ProfileDns(remote = DnsResolver(DnsTransport.DOH, domain = "https://dns.test/dns-query")),
            useChunkFiles = true,
        )
    }

    private fun linkFor(profile: RoutingProfile): String {
        val encoded = Base64.getEncoder().encodeToString(happJson(profile).toByteArray())
        return "happ://routing/add/$encoded"
    }

    private fun invalidDnsLink(): String {
        val withoutDns = happJson(sampleProfile().copy(dns = null)).removeSuffix("}")
        val invalidDns = "$withoutDns,\"RemoteDNSType\":\"DoH\",\"RemoteDNSDomain\":\"not-a-url\"}"
        val encoded = Base64.getEncoder().encodeToString(invalidDns.toByteArray())
        return "happ://routing/add/$encoded"
    }

    /**
     * Happ-shaped JSON that [art.yniyniyni.subspace.core.parser.routing.RoutingProfileImport]
     * round-trips into [profile]. DNS keys are merged at the root so a non-null
     * [RoutingProfile.dns] survives parse as `hasDns`.
     */
    private fun happJson(profile: RoutingProfile): String {
        val members = mutableListOf<String>()
        members += """"Name":"${profile.name}""""
        profile.globalProxy?.let { members += """"GlobalProxy":"$it"""" }
        members +=
            """"RouteOrder":"${profile.routeOrder.joinToString("-") { it.name.lowercase() }}""""
        members += """"DomainStrategy":"${profile.domainStrategy.wireValue}""""
        profile.geoIpUrl?.let { members += """"Geoipurl":"$it"""" }
        profile.geoSiteUrl?.let { members += """"Geositeurl":"$it"""" }
        profile.lastUpdated?.let { members += """"LastUpdated":"$it"""" }
        profile.useChunkFiles?.let { members += """"UseChunkFiles":"$it"""" }
        ProfileDnsCodec
            .encode(profile.dns)
            ?.trim()
            ?.removePrefix("{")
            ?.removeSuffix("}")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { members += it }
        fun bucket(
            sitesKey: String,
            ipsKey: String,
            bucket: RuleBucket,
        ) {
            if (bucket.sites.isNotEmpty()) {
                members += """"$sitesKey":[${bucket.sites.joinToString(",") { "\"$it\"" }}]"""
            }
            if (bucket.ips.isNotEmpty()) {
                members += """"$ipsKey":[${bucket.ips.joinToString(",") { "\"$it\"" }}]"""
            }
        }
        bucket("DirectSites", "DirectIp", profile.bucket(RouteOutcome.DIRECT))
        bucket("ProxySites", "ProxyIp", profile.bucket(RouteOutcome.PROXY))
        bucket("BlockSites", "BlockIp", profile.bucket(RouteOutcome.BLOCK))
        return "{${members.joinToString(",")}}"
    }
}

/**
 * In-memory [RoutingRepository] slice the ViewModel tests seed and assert on.
 * Decide/upsert/observe match the real gates enough that unchanged, replace,
 * and "nothing stored until confirm" are real behaviors, not mock stubs.
 */
private class FakeRoutingRepository {
    private val stored = MutableStateFlow<List<StoredRuleSet>>(emptyList())
    private var nextId = 1L

    fun observeAllStored(): Flow<List<StoredRuleSet>> = stored

    suspend fun upsertProfile(
        profile: RoutingProfile,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): Long {
        val existing = stored.value.firstOrNull { it.ruleSet.name == profile.name }
        val id = existing?.ruleSet?.id ?: nextId++
        val row =
            StoredRuleSet(
                ruleSet = profile.toRuleSet(id),
                sourceKind = sourceKind,
                subscriptionId = subscriptionId,
                lastUpdated = profile.lastUpdated,
                fingerprint = profile.fingerprint(),
                geoIpUrl = profile.geoIpUrl,
                geoSiteUrl = profile.geoSiteUrl,
                hasDns = profile.hasDns,
                assetGeneration = existing?.assetGeneration ?: 0L,
                assetState = existing?.assetState ?: RuleSetAssetState.None,
                assetFailure = existing?.assetFailure,
            )
        stored.value = stored.value.filterNot { it.ruleSet.name == profile.name } + row
        return id
    }

    suspend fun decideFor(profile: RoutingProfile): RoutingRepository.UpdateDecision {
        val existing =
            stored.value.firstOrNull { it.ruleSet.name == profile.name }
                ?: return RoutingRepository.UpdateDecision.New
        val incoming = profile.lastUpdated
        val storedUpdated = existing.lastUpdated
        return when {
            existing.fingerprint == profile.fingerprint() -> RoutingRepository.UpdateDecision.Unchanged
            incoming != null && storedUpdated != null && incoming <= storedUpdated ->
                RoutingRepository.UpdateDecision.Stale
            else -> RoutingRepository.UpdateDecision.Changed
        }
    }
}

private class FakeSettingsRepository {
    private val active = MutableStateFlow<Long?>(null)
    val activeRoutingRuleSetId: Flow<Long?> = active

    fun setActiveRoutingRuleSetId(id: Long?) {
        active.value = id
    }
}

/**
 * Read-only [preview] never records [downloads]; [apply] is the only write and
 * the only place a geo URL is treated as fetched.
 */
// LongParameterList / ReturnCount: each injected failure is a distinct way the
// real importer reports trouble — thrown, or returned as an outcome — and every
// one is defaulted so a test names only the mode it exercises.
@Suppress("LongParameterList")
private class FakeImportReviewSource(
    private val repository: FakeRoutingRepository,
    private val settings: FakeSettingsRepository,
    private val downloads: MutableList<String>,
    private val failApplyWith: Throwable? = null,
    private val failDisableWith: Throwable? = null,
    private val failPreviewWith: Throwable? = null,
    /** A returned (not thrown) outcome, which is how the importer reports a failed install. */
    private val applyOutcome: ImportOutcome? = null,
) : ImportReviewSource {
    override suspend fun preview(profile: RoutingProfile): ImportPreview {
        failPreviewWith?.let { throw it }
        val decision = repository.decideFor(profile)
        val existingId =
            repository.observeAllStored().first().firstOrNull { it.ruleSet.name == profile.name }?.ruleSet?.id
        val activeId = settings.activeRoutingRuleSetId.first()
        return ImportPreview(
            decision = decision,
            profile = profile,
            replacesExisting = existingId != null,
            replacesActive = existingId != null && existingId == activeId,
            willActivate = activeId == null,
            geoFiles = geoPreviews(profile),
        )
    }

    @Suppress("ReturnCount") // Thrown failure, injected outcome and normal path terminate separately.
    override suspend fun apply(
        profile: RoutingProfile,
        verb: RoutingVerb,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): ImportOutcome {
        failApplyWith?.let { throw it }
        applyOutcome?.let { return it }
        val blocked =
            when (repository.decideFor(profile)) {
                RoutingRepository.UpdateDecision.Unchanged -> ImportOutcome.Unchanged
                RoutingRepository.UpdateDecision.Stale -> ImportOutcome.Stale
                RoutingRepository.UpdateDecision.New,
                RoutingRepository.UpdateDecision.Changed,
                -> null
            }
        if (blocked != null) return blocked
        val id = repository.upsertProfile(profile, sourceKind, subscriptionId)
        geoPreviews(profile).forEach { preview ->
            if (!preview.alreadyOnDevice) downloads += preview.url
        }
        val activated =
            when (verb) {
                RoutingVerb.OnAdd -> {
                    settings.setActiveRoutingRuleSetId(id)
                    true
                }
                RoutingVerb.Add -> {
                    if (settings.activeRoutingRuleSetId.first() == null) {
                        settings.setActiveRoutingRuleSetId(id)
                        true
                    } else {
                        false
                    }
                }
                RoutingVerb.Off -> false
            }
        return if (activated) ImportOutcome.Activated(id) else ImportOutcome.Stored(id)
    }

    override suspend fun disableRouting() {
        failDisableWith?.let { throw it }
        settings.setActiveRoutingRuleSetId(null)
    }

    private fun geoPreviews(profile: RoutingProfile): List<GeoFilePreview> {
        val required = profile.toRuleSet().requiredGeoFiles()
        return listOfNotNull(
            profile.geoIpUrl
                ?.takeIf { "geoip.dat" in required }
                ?.let { url ->
                    GeoFilePreview(
                        fileName = "geoip.dat",
                        url = url,
                        approximateBytes = null,
                        alreadyOnDevice = false,
                    )
                },
            profile.geoSiteUrl
                ?.takeIf { "geosite.dat" in required }
                ?.let { url ->
                    GeoFilePreview(
                        fileName = "geosite.dat",
                        url = url,
                        approximateBytes = null,
                        alreadyOnDevice = false,
                    )
                },
        )
    }
}
