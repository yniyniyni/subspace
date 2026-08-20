// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.RoutingRuleSetEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.data.testing.InMemoryRoutingStack
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test

class RoutingRepositoryTest {
    private lateinit var stack: InMemoryRoutingStack

    @Before
    fun setUp() {
        stack = InMemoryRoutingStack(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = stack.close()

    private val sample =
        RoutingRuleSet(
            name = "domestic direct",
            buckets = mapOf(
                RouteOutcome.DIRECT to
                    RuleBucket(sites = listOf("geosite:cn"), ips = listOf("geoip:cn", "10.0.0.0/8")),
                RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
            ),
            order = listOf(RouteOutcome.DIRECT, RouteOutcome.BLOCK, RouteOutcome.PROXY),
            domainStrategy = DomainStrategy.IP_ON_DEMAND,
        )

    private fun sampleProfile(
        name: String = "Provider routing",
        lastUpdated: Long? = SAMPLE_LAST_UPDATED,
    ): RoutingProfile =
        RoutingProfile(
            name = name,
            globalProxy = false,
            routeOrder = listOf(RouteOutcome.DIRECT, RouteOutcome.BLOCK, RouteOutcome.PROXY),
            domainStrategy = DomainStrategy.IP_ON_DEMAND,
            buckets = mapOf(
                RouteOutcome.DIRECT to
                    RuleBucket(sites = listOf("geosite:cn"), ips = listOf("geoip:cn")),
            ),
            geoIpUrl = "https://assets.example/geoip.dat",
            geoSiteUrl = "https://assets.example/geosite.dat",
            lastUpdated = lastUpdated,
            dnsJson =
            """{"RemoteDNSType":"DoH","RemoteDNSDomain":"https://dns.example/dns-query"}""",
            useChunkFiles = true,
        )

    @Test
    fun roundTripsEveryField() = runTest {
        val id = stack.repository.upsert(sample)

        val loaded = stack.repository.ruleSet(id).shouldNotBeNull()

        loaded.name shouldBe sample.name
        loaded.order shouldBe sample.order
        loaded.domainStrategy shouldBe DomainStrategy.IP_ON_DEMAND
        loaded.bucket(RouteOutcome.DIRECT) shouldBe sample.bucket(RouteOutcome.DIRECT)
        loaded.bucket(RouteOutcome.BLOCK) shouldBe sample.bucket(RouteOutcome.BLOCK)
        loaded.bucket(RouteOutcome.PROXY) shouldBe RuleBucket()
    }

    // An empty TEXT column must read back as an empty list, not as a list
    // containing one empty string — String.split("\n") on "" yields [""].
    @Test
    fun anEmptyBucketReadsBackEmpty() = runTest {
        val id = stack.repository.upsert(RoutingRuleSet(name = "empty"))

        val loaded = stack.repository.ruleSet(id).shouldNotBeNull()

        loaded.entryCount shouldBe 0
        loaded.bucket(RouteOutcome.DIRECT).sites shouldBe emptyList()
        loaded.bucket(RouteOutcome.DIRECT).ips shouldBe emptyList()
    }

    @Test
    fun upsertWithAnExistingIdUpdatesRatherThanInserting() = runTest {
        val id = stack.repository.upsert(sample)

        stack.repository.upsert(sample.copy(id = id, name = "renamed"))

        stack.repository.observeAll().first() shouldHaveSize 1
        stack.repository.ruleSet(id).shouldNotBeNull().name shouldBe "renamed"
    }

    @Test
    fun upsertingANewRuleSetWithAnExistingNameUpdatesTheExistingRow() = runTest {
        val id = stack.repository.upsert(sample)
        val replacement =
            sample.copy(
                id = 0,
                buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("domain:proxy.example"))),
                domainStrategy = DomainStrategy.AS_IS,
            )

        stack.repository.upsert(replacement) shouldBe id

        stack.repository.observeAll().first() shouldHaveSize 1
        val loaded = stack.repository.ruleSet(id).shouldNotBeNull()
        loaded.domainStrategy shouldBe DomainStrategy.AS_IS
        loaded.bucket(RouteOutcome.PROXY).sites shouldBe listOf("domain:proxy.example")
        loaded.bucket(RouteOutcome.DIRECT) shouldBe RuleBucket()
    }

    // The rule set editor's save-time collision check (fix round 1, Task 16 Finding 8) reads
    // through this rather than trusting upsert not to silently merge into an unrelated row.
    @Test
    fun ruleSetNamedFindsTheOneRowWithThatName() = runTest {
        val id = stack.repository.upsert(sample)

        stack.repository.ruleSetNamed(sample.name).shouldNotBeNull().id shouldBe id
    }

    @Test
    fun ruleSetNamedIsNullWhenNoRowHasThatName() = runTest {
        stack.repository.upsert(sample)

        stack.repository.ruleSetNamed("no such name") shouldBe null
    }

    @Test
    fun rejectsBlankAndNewlineEntriesWithoutPersistingThem() = runTest {
        val blank =
            RoutingRuleSet(
                name = "blank",
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf(""))),
            )
        val newline =
            RoutingRuleSet(
                name = "newline",
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(ips = listOf("10.0.0.0/8\n10.0.1.0/24"))),
            )

        runCatching { stack.repository.upsert(blank) }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
        runCatching { stack.repository.upsert(newline) }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()

        stack.repository.observeAll().first() shouldHaveSize 0
    }

    @Test
    fun deleteRemovesTheRow() = runTest {
        val id = stack.repository.upsert(sample)

        stack.repository.delete(id)

        stack.repository.ruleSet(id) shouldBe null
        stack.repository.observeAll().first() shouldHaveSize 0
    }

    @Test
    fun observeAllEmitsInNameOrder() = runTest {
        stack.repository.upsert(RoutingRuleSet(name = "zulu"))
        stack.repository.upsert(RoutingRuleSet(name = "alpha"))

        stack.repository.observeAll().first().map { it.name } shouldBe listOf("alpha", "zulu")
    }

    @Test
    fun anUnchangedProfileIsRecognisedAsUnchanged() = runTest {
        val profile = sampleProfile()

        stack.repository.decideFor(profile) shouldBe RoutingRepository.UpdateDecision.New
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)

        stack.repository.decideFor(profile) shouldBe RoutingRepository.UpdateDecision.Unchanged
    }

    @Test
    fun bumpingOnlyLastUpdatedIsStillUnchanged() = runTest {
        val profile = sampleProfile()
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)

        stack.repository.decideFor(profile.copy(lastUpdated = SAMPLE_LAST_UPDATED + 60)) shouldBe
            RoutingRepository.UpdateDecision.Unchanged
    }

    @Test
    fun anIdenticalProfileWithAnOlderTimestampIsStillUnchanged() = runTest {
        val profile = sampleProfile()
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)

        stack.repository.decideFor(profile.copy(lastUpdated = SAMPLE_LAST_UPDATED - 60)) shouldBe
            RoutingRepository.UpdateDecision.Unchanged
    }

    @Test
    fun changedRulesWithANewerTimestampAreChanged() = runTest {
        val profile = sampleProfile()
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)
        val changed =
            profile.copy(
                lastUpdated = SAMPLE_LAST_UPDATED + 60,
                buckets =
                profile.buckets +
                    (RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads"))),
            )

        stack.repository.decideFor(changed) shouldBe RoutingRepository.UpdateDecision.Changed
    }

    @Test
    fun changedRulesWithAnOlderTimestampAreStale() = runTest {
        val profile = sampleProfile()
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)
        val replayed =
            profile.copy(
                lastUpdated = SAMPLE_LAST_UPDATED - 60,
                buckets =
                profile.buckets +
                    (RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads"))),
            )

        stack.repository.decideFor(replayed) shouldBe RoutingRepository.UpdateDecision.Stale
    }

    @Test
    fun changedRulesWithTheSameTimestampAreStale() = runTest {
        val profile = sampleProfile()
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)
        val replayed =
            profile.copy(
                buckets =
                profile.buckets +
                    (RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads"))),
            )

        stack.repository.decideFor(replayed) shouldBe RoutingRepository.UpdateDecision.Stale
    }

    @Test
    fun aStoredProfileWithoutLastUpdatedHasNoStaleGate() = runTest {
        val profile = sampleProfile(lastUpdated = null)
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)
        val changed =
            profile.copy(
                buckets =
                profile.buckets +
                    (RouteOutcome.BLOCK to RuleBucket(sites = listOf("domain:a.test"))),
            )

        stack.repository.decideFor(changed) shouldBe RoutingRepository.UpdateDecision.Changed
    }

    @Test
    fun anIncomingProfileWithoutLastUpdatedHasNoStaleGate() = runTest {
        val profile = sampleProfile()
        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)
        val changed =
            profile.copy(
                lastUpdated = null,
                buckets =
                profile.buckets +
                    (RouteOutcome.BLOCK to RuleBucket(sites = listOf("domain:a.test"))),
            )

        stack.repository.decideFor(changed) shouldBe RoutingRepository.UpdateDecision.Changed
    }

    @Test
    fun aNameCollisionUpdatesOneRowAndPreservesIdentityAndCreationTime() = runTest {
        val database = inMemoryDatabase()
        try {
            val repository = RoutingRepository(database.routingRuleSetDao())
            val first = repository.upsertProfile(sampleProfile(), RoutingSourceKind.Deeplink, null)
            val createdAt = database.routingRuleSetDao().byId(first).shouldNotBeNull().createdAt

            val second =
                repository.upsertProfile(
                    sampleProfile().copy(
                        lastUpdated = SAMPLE_LAST_UPDATED + 60,
                        buckets = emptyMap(),
                    ),
                    RoutingSourceKind.Deeplink,
                    null,
                )

            second shouldBe first
            repository.observeAllStored().first() shouldHaveSize 1
            database.routingRuleSetDao().byId(second).shouldNotBeNull().createdAt shouldBe createdAt
        } finally {
            database.close()
        }
    }

    @Test
    fun aNameCollisionLeavesLiveRulesUntouchedUntilGenerationCommit() = runTest {
        val first = sampleProfile()
        val id = stack.repository.upsertProfile(first, RoutingSourceKind.Header, null)
        val changed =
            first.copy(
                lastUpdated = SAMPLE_LAST_UPDATED + 60,
                buckets =
                mapOf(
                    RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads")),
                ),
            )

        stack.repository.upsertProfile(changed, RoutingSourceKind.Header, null) shouldBe id

        val stillLive = stack.repository.stored(id).shouldNotBeNull()
        stillLive.ruleSet.bucket(RouteOutcome.DIRECT) shouldBe first.bucket(RouteOutcome.DIRECT)
        stillLive.ruleSet.bucket(RouteOutcome.BLOCK).sites.shouldBeEmpty()
        stillLive.fingerprint shouldBe first.fingerprint()
        stillLive.lastUpdated shouldBe SAMPLE_LAST_UPDATED
    }

    @Test
    fun aStaleCollisionSnapshotCannotRestoreAnOlderGeneration() = runTest {
        val database = inMemoryDatabase()
        try {
            val dao = database.routingRuleSetDao()
            val repository = RoutingRepository(dao)
            val initial = sampleProfile()
            val id = repository.upsertProfile(initial, RoutingSourceKind.Header, null)
            val staleSnapshot =
                dao.byId(id).shouldNotBeNull().copy(
                    sourceKind = RoutingSourceKind.Body.wireValue,
                )
            val committed =
                initial.copy(
                    lastUpdated = SAMPLE_LAST_UPDATED + 60,
                    buckets =
                    mapOf(
                        RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:committed")),
                    ),
                    geoIpUrl = "https://committed.example/geoip.dat",
                    geoSiteUrl = null,
                    dnsJson = null,
                    useChunkFiles = false,
                )
            repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.TimedOut)
            repository.commitGeneration(id, committed, generation = 9)
            val published = dao.byId(id).shouldNotBeNull()

            dao.upsertProfileByName(staleSnapshot)

            dao.byId(id).shouldNotBeNull() shouldBe
                published.copy(sourceKind = RoutingSourceKind.Body.wireValue)
        } finally {
            database.close()
        }
    }

    @Test
    fun aCollisionAfterCommitChangesOnlySourceProvenance() = runTest {
        val initial = sampleProfile()
        val id = stack.repository.upsertProfile(initial, RoutingSourceKind.Header, null)
        val committed =
            initial.copy(
                lastUpdated = SAMPLE_LAST_UPDATED + 60,
                buckets =
                mapOf(
                    RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:committed")),
                ),
                geoIpUrl = "https://committed.example/geoip.dat",
                geoSiteUrl = null,
                dnsJson = null,
                useChunkFiles = false,
            )
        stack.repository.commitGeneration(id, committed, generation = 4)
        val subscription =
            stack.subscriptionRepository.add(
                url = "https://provider.example/subscription",
                name = "Provider",
            )
        val laterCollision =
            initial.copy(
                lastUpdated = SAMPLE_LAST_UPDATED + 120,
                buckets =
                mapOf(
                    RouteOutcome.DIRECT to RuleBucket(sites = listOf("domain:must-not-land.test")),
                ),
            )

        stack.repository.upsertProfile(laterCollision, RoutingSourceKind.Body, subscription.id) shouldBe id

        val stored = stack.repository.stored(id).shouldNotBeNull()
        stored.sourceKind shouldBe RoutingSourceKind.Body
        stored.subscriptionId shouldBe subscription.id
        stored.ruleSet.id shouldBe id
        stored.ruleSet.name shouldBe committed.name
        stored.ruleSet.order shouldBe committed.routeOrder
        stored.ruleSet.domainStrategy shouldBe committed.domainStrategy
        stored.ruleSet.globalProxy shouldBe committed.globalProxy
        stored.ruleSet.bucket(RouteOutcome.DIRECT) shouldBe committed.bucket(RouteOutcome.DIRECT)
        stored.ruleSet.bucket(RouteOutcome.PROXY) shouldBe committed.bucket(RouteOutcome.PROXY)
        stored.ruleSet.bucket(RouteOutcome.BLOCK) shouldBe committed.bucket(RouteOutcome.BLOCK)
        stored.lastUpdated shouldBe committed.lastUpdated
        stored.fingerprint shouldBe committed.fingerprint()
        stored.geoIpUrl shouldBe committed.geoIpUrl
        stored.geoSiteUrl shouldBe committed.geoSiteUrl
        stored.hasUnappliedDns shouldBe false
        stored.assetGeneration shouldBe 4L
        stored.assetState shouldBe RuleSetAssetState.Ready
        stored.assetFailure shouldBe null
    }

    @Test
    fun concurrentSameNameProfileInsertsResolveToOneRow() = runTest {
        val profile = sampleProfile()

        val ids =
            withContext(Dispatchers.Default) {
                List(16) {
                    async {
                        stack.repository.upsertProfile(profile, RoutingSourceKind.Header, null)
                    }
                }.awaitAll()
            }

        ids.toSet() shouldHaveSize 1
        stack.repository.observeAllStored().first() shouldHaveSize 1
    }

    @Test
    fun existingIdRenameRetriesWhenItsObservedAliasChangesBeforeLockAcquisition() {
        runBlocking {
            val original = sample.copy(name = "Z current alias")
            val id = stack.repository.upsert(original)
            val staleTarget = original.copy(id = id, name = "A stale target")
            val intervening = original.copy(id = id, name = "B changed alias")
            val staleTargetHeld = CompletableDeferred<Unit>()
            val releaseStaleTarget = CompletableDeferred<Unit>()
            val staleTargetHolder =
                launch {
                    RoutingProfileProcessCoordinator.withProfiles(listOf(staleTarget.name)) {
                        staleTargetHeld.complete(Unit)
                        releaseStaleTarget.await()
                    }
                }
            staleTargetHeld.await()
            val staleRename = async { stack.repository.upsert(staleTarget) }
            delay(WAITER_REGISTRATION_MILLIS)

            stack.repository.upsert(intervening) shouldBe id
            val changedAliasHeld = CompletableDeferred<Unit>()
            val releaseChangedAlias = CompletableDeferred<Unit>()
            val changedAliasHolder =
                launch {
                    RoutingProfileProcessCoordinator.withProfiles(listOf(intervening.name)) {
                        changedAliasHeld.complete(Unit)
                        releaseChangedAlias.await()
                    }
                }
            changedAliasHeld.await()

            releaseStaleTarget.complete(Unit)
            withTimeoutOrNull(CONCURRENCY_PROBE_MILLIS) { staleRename.await() } shouldBe null
            stack.repository.ruleSet(id).shouldNotBeNull().name shouldBe intervening.name

            releaseChangedAlias.complete(Unit)
            staleRename.await() shouldBe id
            staleTargetHolder.join()
            changedAliasHolder.join()
            stack.repository.ruleSet(id).shouldNotBeNull().name shouldBe staleTarget.name
        }
    }

    @Test
    fun storedRuleSetCarriesEveryProfileFieldAndRedactsSensitiveValues() = runTest {
        val profile = sampleProfile()
        val id = stack.repository.upsertProfile(profile, RoutingSourceKind.Body, null)

        val stored = stack.repository.stored(id).shouldNotBeNull()

        stored.ruleSet.id shouldBe id
        stored.ruleSet.name shouldBe profile.name
        stored.ruleSet.globalProxy shouldBe false
        stored.ruleSet.order shouldBe profile.routeOrder
        stored.ruleSet.domainStrategy shouldBe profile.domainStrategy
        stored.ruleSet.bucket(RouteOutcome.DIRECT) shouldBe profile.bucket(RouteOutcome.DIRECT)
        stored.sourceKind shouldBe RoutingSourceKind.Body
        stored.subscriptionId shouldBe null
        stored.lastUpdated shouldBe SAMPLE_LAST_UPDATED
        stored.fingerprint shouldBe profile.fingerprint()
        stored.geoIpUrl shouldBe profile.geoIpUrl
        stored.geoSiteUrl shouldBe profile.geoSiteUrl
        stored.hasUnappliedDns shouldBe true
        stored.assetGeneration shouldBe 0L
        stored.assetState shouldBe RuleSetAssetState.None
        stored.assetFailure shouldBe null
        stored.toString() shouldNotContain "geosite:cn"
        stored.toString() shouldNotContain "assets.example"
        stored.toString() shouldNotContain "dns.example"
    }

    @Test
    fun sourceKindUsesTheStableLowercaseWireValue() = runTest {
        val database = inMemoryDatabase()
        try {
            val repository = RoutingRepository(database.routingRuleSetDao())

            val id = repository.upsertProfile(sampleProfile(), RoutingSourceKind.Header, null)

            database.routingRuleSetDao().byId(id).shouldNotBeNull().sourceKind shouldBe "header"
        } finally {
            database.close()
        }
    }

    @Test
    fun unknownStoredProvenanceDoesNotCrashTheRepository() = runTest {
        val database = inMemoryDatabase()
        try {
            val dao = database.routingRuleSetDao()
            val id =
                dao.insert(
                    rawEntity(
                        sourceKind = "Header",
                        assetState = "FutureState",
                        assetFailure = "FutureFailure",
                    ),
                )
            val repository = RoutingRepository(dao)

            val stored = repository.stored(id).shouldNotBeNull()

            stored.sourceKind shouldBe null
            stored.assetState shouldBe RuleSetAssetState.None
            stored.assetFailure shouldBe null
        } finally {
            database.close()
        }
    }

    @Test
    fun markAssetsWritesStateAndFailureAsOnePair() = runTest {
        val id =
            stack.repository.upsertProfile(
                sampleProfile(),
                RoutingSourceKind.Clipboard,
                subscriptionId = null,
            )

        stack.repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.TimedOut)
        stack.repository.stored(id).shouldNotBeNull().let { stored ->
            stored.assetState shouldBe RuleSetAssetState.Failed
            stored.assetFailure shouldBe RuleSetAssetFailure.TimedOut
        }

        stack.repository.markAssets(id, RuleSetAssetState.Pending)
        stack.repository.stored(id).shouldNotBeNull().let { stored ->
            stored.assetState shouldBe RuleSetAssetState.Pending
            stored.assetFailure shouldBe null
        }
    }

    @Test
    fun committingAGenerationWritesRulesMetadataAndStateTogether() = runTest {
        val id = stack.repository.upsertProfile(sampleProfile(), RoutingSourceKind.Qr, null)
        stack.repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.DownloadFailed)
        val updated =
            sampleProfile().copy(
                globalProxy = true,
                routeOrder = RoutingRuleSet.DEFAULT_ORDER,
                domainStrategy = DomainStrategy.AS_IS,
                lastUpdated = SAMPLE_LAST_UPDATED + 60,
                buckets =
                mapOf(
                    RouteOutcome.DIRECT to
                        RuleBucket(
                            sites = listOf("domain:direct.test"),
                            ips = listOf("10.0.0.0/8"),
                        ),
                    RouteOutcome.PROXY to
                        RuleBucket(
                            sites = listOf("geosite:blocked"),
                            ips = listOf("geoip:us"),
                        ),
                    RouteOutcome.BLOCK to
                        RuleBucket(
                            sites = listOf("geosite:ads"),
                            ips = listOf("203.0.113.0/24"),
                        ),
                ),
                geoIpUrl = "https://next.example/geoip.dat",
                geoSiteUrl = null,
                dnsJson = null,
                useChunkFiles = false,
            )

        stack.repository.commitGeneration(id, updated, generation = 1)

        val stored = stack.repository.stored(id).shouldNotBeNull()
        stored.assetGeneration shouldBe 1L
        stored.assetState shouldBe RuleSetAssetState.Ready
        stored.assetFailure shouldBe null
        stored.ruleSet.globalProxy shouldBe true
        stored.ruleSet.order shouldBe RoutingRuleSet.DEFAULT_ORDER
        stored.ruleSet.domainStrategy shouldBe DomainStrategy.AS_IS
        stored.ruleSet.bucket(RouteOutcome.DIRECT) shouldBe updated.bucket(RouteOutcome.DIRECT)
        stored.ruleSet.bucket(RouteOutcome.PROXY) shouldBe updated.bucket(RouteOutcome.PROXY)
        stored.ruleSet.bucket(RouteOutcome.BLOCK) shouldBe updated.bucket(RouteOutcome.BLOCK)
        stored.lastUpdated shouldBe SAMPLE_LAST_UPDATED + 60
        stored.fingerprint shouldBe updated.fingerprint()
        stored.geoIpUrl shouldBe updated.geoIpUrl
        stored.geoSiteUrl shouldBe null
        stored.hasUnappliedDns shouldBe false
    }

    @Test
    fun deletingASubscriptionCascadesToItsProfiles() = runTest {
        val subscription =
            stack.subscriptionRepository.add(
                url = "https://subscription.example/config",
                name = "Provider",
            )
        stack.repository.upsertProfile(sampleProfile(), RoutingSourceKind.Header, subscription.id)
        stack.repository.observeAllStored().first() shouldHaveSize 1
        stack.repository.observeAllStored().first().single().subscriptionId shouldBe subscription.id

        stack.subscriptionRepository.delete(subscription.id)

        stack.repository.observeAllStored().first().shouldBeEmpty()
    }

    private fun inMemoryDatabase(): SubspaceDatabase =
        Room
            .inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                SubspaceDatabase::class.java,
            ).allowMainThreadQueries()
            .build()

    private fun rawEntity(
        sourceKind: String?,
        assetState: String,
        assetFailure: String?,
    ): RoutingRuleSetEntity =
        RoutingRuleSetEntity(
            name = "raw",
            directSites = "",
            directIps = "",
            proxySites = "",
            proxyIps = "",
            blockSites = "",
            blockIps = "",
            routeOrder = "BLOCK,PROXY,DIRECT",
            domainStrategy = "IP_IF_NON_MATCH",
            createdAt = 1L,
            sourceKind = sourceKind,
            assetState = assetState,
            assetFailure = assetFailure,
        )

    private companion object {
        const val CONCURRENCY_PROBE_MILLIS = 1_000L
        const val WAITER_REGISTRATION_MILLIS = 100L
        const val SAMPLE_LAST_UPDATED = 1_700_000_000L
    }
}
