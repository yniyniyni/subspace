// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
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
import space.getsub.core.data.db.ProfileGroupEntity
import space.getsub.core.data.db.RoutingRuleSetEntity
import space.getsub.core.data.db.SubscriptionEntity
import space.getsub.core.data.db.SubspaceDatabase
import space.getsub.core.data.testing.InMemoryRoutingStack
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.ProfileDns
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingProfile
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.RoutingSourceKind
import space.getsub.core.model.RuleBucket
import space.getsub.core.model.RuleSetAssetFailure
import space.getsub.core.model.RuleSetAssetState

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
            dns = ProfileDns(remote = DnsResolver(DnsTransport.DOH, domain = "https://dns.example/dns-query")),
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

    /**
     * Stores [profile] the way a completed import leaves it.
     *
     * `upsertProfile` alone is only step 1 of spec §7.4 — it writes the row as
     * `Pending` and publishes nothing. The `decideFor` gates below describe what
     * happens when a provider re-delivers content that already **landed**, so
     * their fixture has to reach the state `commitGeneration` produces. Setting
     * up with the bare upsert made these tests assert gate behaviour against a
     * half-finished import, which is the state §7.5 requires to stay retryable.
     */
    private suspend fun publish(profile: RoutingProfile) {
        val id = stack.repository.upsertProfile(profile, RoutingSourceKind.Header, subscriptionId = null)
        stack.repository.commitGeneration(id, profile, generation = 0L)
    }

    @Test
    fun anUnchangedProfileIsRecognisedAsUnchanged() = runTest {
        val profile = sampleProfile()

        stack.repository.decideFor(profile) shouldBe RoutingRepository.UpdateDecision.New
        publish(profile)

        stack.repository.decideFor(profile) shouldBe RoutingRepository.UpdateDecision.Unchanged
    }

    /**
     * Branch review finding 1, and spec §9's mandated case: M6.5 changed
     * `fingerprint()` from one `feed(dnsJson)` to a fold over the typed DNS
     * projection, which changes the digest of **every** stored profile — a
     * DNS-less one included, because `feed(null)` still writes a separator.
     * Comparing against the stored column therefore answered `Changed` (a
     * spurious review sheet) or `Stale` (a real provider update silently
     * refused) for every row on the first sync after upgrading.
     */
    @Test
    fun aRowWhoseStoredFingerprintPredatesTheCurrentAlgorithmIsStillUnchanged() = runTest {
        val profile = sampleProfile()
        publish(profile)
        stack.forgeStoredFingerprints("an-m6-era-digest-this-algorithm-would-never-produce")

        stack.repository.decideFor(profile) shouldBe RoutingRepository.UpdateDecision.Unchanged
    }

    @Test
    fun bumpingOnlyLastUpdatedIsStillUnchanged() = runTest {
        val profile = sampleProfile()
        publish(profile)

        stack.repository.decideFor(profile.copy(lastUpdated = SAMPLE_LAST_UPDATED + 60)) shouldBe
            RoutingRepository.UpdateDecision.Unchanged
    }

    @Test
    fun anIdenticalProfileWithAnOlderTimestampIsStillUnchanged() = runTest {
        val profile = sampleProfile()
        publish(profile)

        stack.repository.decideFor(profile.copy(lastUpdated = SAMPLE_LAST_UPDATED - 60)) shouldBe
            RoutingRepository.UpdateDecision.Unchanged
    }

    @Test
    fun changedRulesWithANewerTimestampAreChanged() = runTest {
        val profile = sampleProfile()
        publish(profile)
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
        publish(profile)
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
        publish(profile)
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
        publish(profile)
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
        publish(profile)
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
                    dns = null,
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
                dns = null,
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
        stored.hasDns shouldBe false
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
        stored.hasDns shouldBe true
        // Fix round 1, Finding 3: the milestone's specific failure mode is a
        // stored DNS block that decodes fine in isolation but never reaches
        // anything downstream — a green suite over an inert tunnel. This is the
        // guard for the first hop of that chain: a row with a DNS block must
        // expose a non-null, correctly decoded StoredRuleSet.dns, not just a
        // true hasDns flag.
        stored.dns shouldBe profile.dns
        stored.assetGeneration shouldBe 0L
        // §7.4 step 1: "Approved import writes the row with assetState = Pending."
        stored.assetState shouldBe RuleSetAssetState.Pending
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
                dns = null,
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
        stored.hasDns shouldBe false
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

class RoutingRepositoryEditorSaveTest {
    @Test
    fun editingAnExistingRuleSetDoesNotRestoreAStaleGlobalProxySnapshot() = runTest {
        val database = editorSaveDatabase()
        try {
            val seeded = seedLifecycleRow(database)
            val repository = RoutingRepository(database.routingRuleSetDao())
            val editorSnapshot = repository.ruleSet(seeded.id).shouldNotBeNull()

            database.routingRuleSetDao().commitGeneration(
                id = seeded.id,
                directSites = seeded.entity.directSites,
                directIps = seeded.entity.directIps,
                proxySites = seeded.entity.proxySites,
                proxyIps = seeded.entity.proxyIps,
                blockSites = seeded.entity.blockSites,
                blockIps = seeded.entity.blockIps,
                routeOrder = seeded.entity.routeOrder,
                domainStrategy = seeded.entity.domainStrategy,
                globalProxy = true,
                lastUpdated = seeded.entity.lastUpdated,
                fingerprint = seeded.entity.fingerprint,
                geoIpUrl = seeded.entity.geoIpUrl,
                geoSiteUrl = seeded.entity.geoSiteUrl,
                dnsJson = seeded.entity.dnsJson,
                useChunkFiles = seeded.entity.useChunkFiles,
                generation = seeded.entity.assetGeneration,
            )

            repository.upsert(
                editorSnapshot.copy(
                    buckets =
                    editorSnapshot.buckets +
                        (
                            RouteOutcome.DIRECT to
                                RuleBucket(
                                    sites = listOf("domain:edited-after-import.test"),
                                    ips = editorSnapshot.bucket(RouteOutcome.DIRECT).ips,
                                )
                            ),
                ),
            )

            val stored = database.routingRuleSetDao().byId(seeded.id).shouldNotBeNull()
            stored.globalProxy shouldBe true
            stored.directSites shouldBe "domain:edited-after-import.test"
        } finally {
            database.close()
        }
    }

    @Test
    fun editingAnExistingRuleSetPreservesEveryLifecycleColumn() = runTest {
        val database = editorSaveDatabase()
        try {
            val seeded = seedLifecycleRow(database)
            val edited = editedRuleSet(seeded.id)
            val repository = RoutingRepository(database.routingRuleSetDao())

            repository.upsert(edited) shouldBe seeded.id

            database.routingRuleSetDao().byId(seeded.id).shouldNotBeNull() shouldBe
                seeded.entity.copy(
                    id = seeded.id,
                    name = edited.name,
                    directSites = "domain:new-direct.test",
                    directIps = "10.1.0.0/16",
                    proxySites = "domain:new-proxy.test",
                    proxyIps = "203.0.113.0/24",
                    blockSites = "domain:new-block.test",
                    blockIps = "2001:db8::/32",
                    routeOrder = "PROXY,DIRECT,BLOCK",
                    domainStrategy = DomainStrategy.AS_IS.name,
                    globalProxy = false,
                )
        } finally {
            database.close()
        }
    }

    @Test
    fun insertingANewManualRuleSetKeepsManualLifecycleDefaults() = runTest {
        val database = editorSaveDatabase()
        try {
            val repository = RoutingRepository(database.routingRuleSetDao())

            val id = repository.upsert(RoutingRuleSet(name = "manual"))

            val stored = database.routingRuleSetDao().byId(id).shouldNotBeNull()
            stored.sourceKind shouldBe null
            stored.subscriptionId shouldBe null
            stored.lastUpdated shouldBe null
            stored.fingerprint shouldBe null
            stored.geoIpUrl shouldBe null
            stored.geoSiteUrl shouldBe null
            stored.globalProxy shouldBe null
            stored.dnsJson shouldBe null
            stored.useChunkFiles shouldBe null
            stored.assetGeneration shouldBe 0L
            stored.assetState shouldBe RuleSetAssetState.None.name
            stored.assetFailure shouldBe null
        } finally {
            database.close()
        }
    }
}

private data class SeededLifecycleRow(
    val id: Long,
    val entity: RoutingRuleSetEntity,
)

private suspend fun seedLifecycleRow(database: SubspaceDatabase): SeededLifecycleRow {
    val subscriptionId = insertSubscription(database)
    val entity = lifecycleEntity(subscriptionId)
    return SeededLifecycleRow(database.routingRuleSetDao().insert(entity), entity)
}

private suspend fun insertSubscription(database: SubspaceDatabase): Long {
    val groupId =
        database.profileDao().insertGroup(
            ProfileGroupEntity(
                name = "Provider",
                source = "SUBSCRIPTION",
                position = 0,
                createdAt = 11L,
            ),
        )
    return database.subscriptionDao().insertSubscription(
        SubscriptionEntity(
            groupId = groupId,
            url = "https://provider.example/subscription",
            userAgentOverride = "ProviderClient/1",
            hwidEnabled = true,
            lastFetchedAt = 21L,
            lastAttemptedAt = 22L,
            lastFetchStatus = "NoServers",
            lastFetchDetail = "EmptyBody",
            createdAt = 12L,
        ),
    )
}

private fun lifecycleEntity(subscriptionId: Long): RoutingRuleSetEntity =
    RoutingRuleSetEntity(
        name = "provider routing",
        directSites = "domain:old-direct.test",
        directIps = "10.0.0.0/8",
        proxySites = "domain:old-proxy.test",
        proxyIps = "192.0.2.0/24",
        blockSites = "domain:old-block.test",
        blockIps = "198.51.100.0/24",
        routeOrder = "BLOCK,PROXY,DIRECT",
        domainStrategy = DomainStrategy.IP_IF_NON_MATCH.name,
        createdAt = 31L,
        sourceKind = RoutingSourceKind.Header.wireValue,
        subscriptionId = subscriptionId,
        lastUpdated = 1_700_000_123L,
        fingerprint = "preserved-fingerprint",
        geoIpUrl = "https://assets.example/geoip.dat",
        geoSiteUrl = "https://assets.example/geosite.dat",
        globalProxy = false,
        dnsJson = "{\"FakeDNS\":\"true\"}",
        useChunkFiles = true,
        assetGeneration = 9L,
        assetState = RuleSetAssetState.Failed.name,
        assetFailure = RuleSetAssetFailure.TimedOut.name,
    )

private fun editedRuleSet(id: Long): RoutingRuleSet =
    RoutingRuleSet(
        id = id,
        name = "edited routing",
        buckets =
        mapOf(
            RouteOutcome.DIRECT to
                RuleBucket(
                    sites = listOf("domain:new-direct.test"),
                    ips = listOf("10.1.0.0/16"),
                ),
            RouteOutcome.PROXY to
                RuleBucket(
                    sites = listOf("domain:new-proxy.test"),
                    ips = listOf("203.0.113.0/24"),
                ),
            RouteOutcome.BLOCK to
                RuleBucket(
                    sites = listOf("domain:new-block.test"),
                    ips = listOf("2001:db8::/32"),
                ),
        ),
        order = listOf(RouteOutcome.PROXY, RouteOutcome.DIRECT, RouteOutcome.BLOCK),
        domainStrategy = DomainStrategy.AS_IS,
        globalProxy = false,
    )

private fun editorSaveDatabase(): SubspaceDatabase =
    Room
        .inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SubspaceDatabase::class.java,
        ).allowMainThreadQueries()
        .build()
