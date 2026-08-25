// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.RoutingProfileDeletion
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.RuleSetAssets
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.TunnelProxyLocator
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.network.FetchFailure
import art.yniyniyni.subspace.core.network.FetchOutcome
import art.yniyniyni.subspace.core.network.HwidProvider
import art.yniyniyni.subspace.core.network.SubscriptionRequest
import art.yniyniyni.subspace.core.network.SubscriptionSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

// The brief's verbatim helper used pbk=abc, which VlessLink's already-implemented
// (Task 1-5) validateRealityPublicKey rejects outright: a REALITY public key must be
// exactly 43 base64url chars (32 raw bytes), never 3. Every link this helper built was
// therefore unparseable, and every test using it silently degraded to SyncResult.NoServers
// instead of exercising the reconciliation path it was written to test. Swapped for a real
// (arbitrary, non-secret) 32-byte X25519-shaped key so the fixture actually parses.
//
// The brief's helper also gave every server the same fixed UUID regardless of [name]. That
// makes Tokyo and Osaka byte-identical *outbounds* (identityHash is derived from the outbound
// alone, never the name — that is the whole point of subscriptionKey being a separate axis,
// spec D8), which collides on ProfileEntity's (groupId, identityHash) unique index exactly the
// way spec §4.3's documented, bounded limitation describes. A real subscription would not
// hand out two genuinely identical servers under different names, so [uuidFor] derives a
// distinct, deterministic (same name -> same uuid, across fetches) credential per name — the
// fixture now models distinct servers the way a real subscription does, which is what these
// reconciliation tests are meant to exercise.
private fun uuidFor(name: String): String {
    val last12 = name.hashCode().toUInt().toString(16).padStart(8, '0').let { (it + it).take(12) }
    return "11111111-2222-3333-4444-$last12"
}

// [uuid] defaults to uuidFor(name) but can be overridden so a fixture can put one server's
// content under a different server's label — see the name-swap and identical-rename tests below.
private fun link(name: String, host: String = "example.com", uuid: String = uuidFor(name)) =
    "vless://$uuid@$host:443?" +
        "security=reality&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&sni=example.org&fp=chrome#$name"

// Backtick names with spaces are avoided here for the same reason
// ProfileRepositoryTest avoids them: runTest {}'s lambda inherits the
// enclosing test method's JVM name, and this module's minSdk 26 makes D8
// reject spaces in the resulting synthetic class name below DEX 040.
/**
 * A routing deeplink a provider could send. §5.6: the payload is a fixture
 * profile with no real domain in it.
 */
private val ROUTING_HEADER_LINK =
    "happ://routing/add/" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"Name":"FromHeader","DirectSites":["domain:header.example"]}""".toByteArray(),
        )

class SubscriptionSyncerTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var subscriptions: SubscriptionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var profiles: ProfileRepository
    private lateinit var root: File

    private var response: FetchOutcome = FetchOutcome.Success("", emptyMap())
    private var lastRequest: SubscriptionRequest? = null
    private var responseForRequest: suspend (SubscriptionRequest) -> FetchOutcome = { response }
    private val source = SubscriptionSource { request ->
        lastRequest = request
        responseForRequest(request)
    }

    /** No tunnel bound by default; individual tests override to assert the port is threaded through. */
    private var proxyPort: Int? = null

    private fun syncer() =
        SubscriptionSyncer(db.subscriptionDao(), subscriptions, settings, source, TunnelProxyLocator { proxyPort })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SubspaceDatabase::class.java,
        ).build()
        profiles = ProfileRepository(db.profileDao())
        settings = SettingsRepository(db.settingDao(), HwidProvider { "test-hwid" })
        root =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                "subscription-syncer-${System.nanoTime()}",
            ).apply { mkdirs() }
        val deletion =
            RoutingProfileDeletion(
                db,
                RoutingRepository(db.routingRuleSetDao()),
                RuleSetAssets(root),
                settings,
                profiles,
            )
        subscriptions = SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
        responseForRequest = { response }
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private suspend fun addSubscription() =
        subscriptions.add("https://example.com/sub", name = "Provider").id

    // M6 Task 16. The `routing` header is the channel §A.1's threat model is
    // written about: whoever controls the subscription URL can use it to change
    // what is proxied and which host the device downloads geo data from. A sync
    // stores the directive and applies nothing (rule 1) — the routing screen
    // raises the review sheet over it.
    @Test
    fun aRoutingHeaderIsStoredAsADirectiveAndAppliesNothing() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success(link("Tokyo"), mapOf("routing" to ROUTING_HEADER_LINK))

        syncer().sync(id)

        subscriptions.effective(id, "routing", default = null).value shouldBe ROUTING_HEADER_LINK
        RoutingRepository(db.routingRuleSetDao()).observeAllStored().first().shouldBeEmpty()
        settings.activeRoutingRuleSetId.first() shouldBe null
    }

    // A bare deeplink is the body form of the same directive. Without the
    // splitter consuming it, this line reaches SubscriptionParser, which reads
    // it as a server (§A.1) — so the server count is what pins it.
    @Test
    fun aBareRoutingLinkInTheBodyDoesNotBecomeAServer() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success("$ROUTING_HEADER_LINK\n${link("Tokyo")}", emptyMap())

        syncer().sync(id)

        subscriptions.effective(id, "routing", default = null).value shouldBe ROUTING_HEADER_LINK
        profiles.observeGroups().first().single().profiles.map { it.name } shouldBe listOf("Tokyo")
    }

    // Header wins over body (§A.1), and the body line is still consumed.
    @Test
    fun aRoutingHeaderWinsOverABodyLine() = runTest {
        val id = addSubscription()
        response =
            FetchOutcome.Success(
                "happ://routing/add/frombody\n${link("Tokyo")}",
                mapOf("routing" to ROUTING_HEADER_LINK),
            )

        syncer().sync(id)

        subscriptions.effective(id, "routing", default = null).value shouldBe ROUTING_HEADER_LINK
        profiles.observeGroups().first().single().profiles.map { it.name } shouldBe listOf("Tokyo")
    }

    @Test
    fun aFirstSyncInsertsEveryServer() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())

        val result = syncer().sync(id)

        result shouldBe SyncResult.Synced(added = 2, updated = 0, removed = 0, keptActive = 0, rejectedDirectives = 0)
    }

    @Test
    fun aDirectiveFromTheResponseIsStored() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success(
            link("Tokyo"),
            mapOf("profile-title" to "Name VPN", "profile-update-interval" to "6"),
        )

        syncer().sync(id)

        subscriptions.effective(id, "profile-update-interval", null).value shouldBe "6"
    }

    @Test
    fun globalHwidGateDisablesTheHeaderEvenWhenTheSubscriptionAllowsIt() = runTest {
        val id = addSubscription()
        settings.setHwidEnabled(false)

        syncer().sync(id)

        lastRequest?.hwidEnabled shouldBe false
    }

    @Test
    fun aRunningTunnelsProxyPortIsThreadedIntoTheFetch() = runTest {
        val id = addSubscription()
        proxyPort = 18080

        syncer().sync(id)

        lastRequest?.proxyPort shouldBe 18080
    }

    @Test
    fun noBoundTunnelFetchesDirectly() = runTest {
        // §5.4/spec: a background refresh with nothing bound gets null and
        // goes direct rather than failing the sync outright.
        val id = addSubscription()
        proxyPort = null

        syncer().sync(id)

        lastRequest?.proxyPort shouldBe null
    }

    @Test
    fun profileTitleRenamesTheGroup() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success(link("Tokyo"), mapOf("profile-title" to "Name VPN"))

        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        profiles.observeGroups().first().single { it.id == groupId }.name shouldBe "Name VPN"
    }

    @Test
    fun anUnchangedServerIsUpdatedInPlaceAndKeepsLastConnectedAt() = runTest {
        // The whole reason sync keys on subscriptionKey rather than
        // identityHash (spec D8): a rotation must not reset "Last used".
        val id = addSubscription()
        response = FetchOutcome.Success(link("Tokyo"), emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val profileId = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single().id
        profiles.recordConnected(profileId, atEpochMillis = 999)

        // Same name, different address (host) — a new identityHash, the same subscriptionKey.
        response = FetchOutcome.Success(link("Tokyo", host = "moved.example.com"), emptyMap())
        val result = syncer().sync(id)

        result shouldBe SyncResult.Synced(0, 1, 0, 0, 0)
        val after = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single()
        after.lastConnectedAt shouldBe 999L
        after.address shouldBe "moved.example.com"
    }

    @Test
    fun aServerAbsentFromTheResponseIsDeleted() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        response = FetchOutcome.Success(link("Tokyo"), emptyMap())
        val result = syncer().sync(id)

        result shouldBe SyncResult.Synced(0, 1, 1, 0, 0)
    }

    @Test
    fun theActiveServerIsKeptAndFlaggedWhenTheProviderDropsIt() = runTest {
        // Spec D4: deleting the row under a running tunnel leaves §5.5's single
        // source of truth holding a profileId that no longer resolves.
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val tokyo = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Tokyo" }

        response = FetchOutcome.Success(link("Osaka"), emptyMap())
        // Production callers do not pass an id to sync(); the authoritative active profile is
        // SettingsRepository's cross-process Room value. Pin this real path so a future default
        // cannot delete the server the tunnel is using.
        settings.setActiveProfile(tokyo.id)
        val result = syncer().sync(id)

        result shouldBe SyncResult.Synced(0, 1, 0, keptActive = 1, rejectedDirectives = 0)
        profiles.profile(tokyo.id) shouldNotBe null
        // The name says "flagged" — this is what makes that true rather than aspirational
        // (Task 11 review fix, Important 4). Part 2's UI reads this to render "no longer
        // offered by this provider".
        profiles.profile(tokyo.id)!!.droppedFromSubscriptionAt shouldNotBe null
    }

    @Test
    fun aProfileActivatedDuringFetchIsKeptWhenTheResponseDropsIt() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val group = profiles.observeGroups().first().single { it.id == groupId }
        val osaka = group.profiles.single { it.name == "Osaka" }

        val fetchStarted = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        responseForRequest = {
            fetchStarted.complete(Unit)
            releaseFetch.await()
            FetchOutcome.Success(link("Tokyo"), emptyMap())
        }

        val sync = async { syncer().sync(id) }
        fetchStarted.await()
        settings.setActiveProfile(osaka.id)
        releaseFetch.complete(Unit)

        sync.await() shouldBe SyncResult.Synced(0, 1, 0, keptActive = 1, rejectedDirectives = 0)
        profiles.profile(osaka.id) shouldNotBe null
        profiles.profile(osaka.id)!!.droppedFromSubscriptionAt shouldNotBe null
    }

    @Test
    fun twoServersSwappingNamesBothSurviveWithHashesTraded() = runTest {
        // Task 11 review fix, Important 5(a). Each name keeps its own subscriptionKey (unique,
        // content-independent), so both rows are matched and updated in place rather than
        // deleted/inserted — but the identityHash values behind those two stable keys must trade
        // places within SubscriptionDao.applySync's one transaction. See its KDoc for why the
        // clearIdentityHash pass makes that safe.
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val tokyoBefore = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Tokyo" }
        val osakaBefore = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Osaka" }
        val tokyoHashBefore = db.profileDao().profile(tokyoBefore.id)!!.identityHash
        val osakaHashBefore = db.profileDao().profile(osakaBefore.id)!!.identityHash

        // The content that used to be "Tokyo" is now labelled "Osaka", and vice versa.
        response = FetchOutcome.Success(
            "${link("Osaka", uuid = uuidFor("Tokyo"))}\n${link("Tokyo", uuid = uuidFor("Osaka"))}",
            emptyMap(),
        )
        val result = syncer().sync(id)

        result shouldBe SyncResult.Synced(0, 2, 0, 0, 0)

        val tokyoAfter = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Tokyo" }
        val osakaAfter = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Osaka" }

        // Same rows survive — subscriptionKey stayed stable, so ids are unchanged.
        tokyoAfter.id shouldBe tokyoBefore.id
        osakaAfter.id shouldBe osakaBefore.id

        // Content traded: the row keyed "Tokyo" now holds what was "Osaka"'s uuid, and the
        // stored identityHash values traded to match, with no leftover clearIdentityHash
        // placeholder ever persisted.
        (tokyoAfter.outbound as VlessOutbound).uuid shouldBe uuidFor("Osaka")
        (osakaAfter.outbound as VlessOutbound).uuid shouldBe uuidFor("Tokyo")
        db.profileDao().profile(tokyoAfter.id)!!.identityHash shouldBe osakaHashBefore
        db.profileDao().profile(osakaAfter.id)!!.identityHash shouldBe tokyoHashBefore
    }

    @Test
    fun renamingTheActiveServerWithAnIdenticalOutboundKeepsItsProfileId() = runTest {
        // Task 11 review fix, Important 5(b) — this is the exact regression Critical 1 named:
        // a provider renaming a node (routine — names carry traffic/expiry counters) while the
        // outbound is unchanged used to insert-or-replace its way into deleting the active row
        // it collided with, while still reporting Synced(keptActive = 1). The active row must
        // survive under its own id, and the collision must be visible (duplicatesDropped), not
        // silent.
        val id = addSubscription()
        response = FetchOutcome.Success(link("Tokyo"), emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val tokyo = profiles.observeGroups().first().single { it.id == groupId }.profiles.single()

        // Same outbound (same uuid), new name/key, while it is the active profile.
        response = FetchOutcome.Success(link("Tokyo Renamed", uuid = uuidFor("Tokyo")), emptyMap())
        val result = syncer().sync(id, activeProfileId = tokyo.id)

        result shouldBe SyncResult.Synced(
            added = 0,
            updated = 0,
            removed = 0,
            keptActive = 1,
            rejectedDirectives = 0,
            duplicatesDropped = 1,
        )
        profiles.profile(tokyo.id) shouldNotBe null
        profiles.profile(tokyo.id)!!.name shouldBe "Tokyo"
    }

    @Test
    fun theFlagClearsWhenTheServerReappears() = runTest {
        // Task 11 review round 2: upsertBySubscriptionKey's update branch does not carry
        // droppedFromSubscriptionAt over from the existing row (see its KDoc), so a rebuilt
        // entity's null overwrites the flag the moment the row's subscriptionKey reappears in a
        // response. That is correct behaviour, but nothing pinned the third leg of the D4
        // lifecycle (drop -> flag set -> server returns -> flag null) until now — a flag that
        // sets but never clears is a permanent false warning.
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val tokyo = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Tokyo" }

        // Dropped while active: the flag is set (pinned separately by
        // theActiveServerIsKeptAndFlaggedWhenTheProviderDropsIt).
        response = FetchOutcome.Success(link("Osaka"), emptyMap())
        syncer().sync(id, activeProfileId = tokyo.id)
        profiles.profile(tokyo.id)!!.droppedFromSubscriptionAt shouldNotBe null

        // The provider offers Tokyo again.
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id, activeProfileId = tokyo.id)

        profiles.profile(tokyo.id)!!.droppedFromSubscriptionAt shouldBe null
    }

    @Test
    fun aMisconfiguredDuplicateAgainstAnUnrelatedRowDoesNotThrow() = runTest {
        // Task 11 review round 2's Important finding: Tokyo and Osaka start with distinct
        // content. The provider then misconfigures Tokyo onto Osaka's exact outbound while
        // leaving Osaka's own entry unchanged, so both entries in this response describe the
        // same content. buildUpserts keeps whichever entry claims the shared hash first (Tokyo,
        // by response order) and drops Osaka's duplicate entry -- but Osaka's *row* is not in
        // upserts and still holds its real, real hash, which is exactly the value Tokyo's row is
        // about to be written with. Before this fix, applySync's clear pass only neutralised
        // rows that were themselves in upserts, so Tokyo's write collided with Osaka's
        // still-real hash and SQLiteConstraintException escaped sync() uncaught. It must not.
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        val osaka = profiles.observeGroups().first()
            .single { it.id == groupId }.profiles.single { it.name == "Osaka" }

        // Both entries now report Osaka's outbound; Tokyo's entry appears first in the response.
        response = FetchOutcome.Success(
            "${link("Tokyo", uuid = uuidFor("Osaka"))}\n${link("Osaka", uuid = uuidFor("Osaka"))}",
            emptyMap(),
        )
        val result = syncer().sync(id)

        result shouldBe SyncResult.Synced(0, 1, 0, keptActive = 0, rejectedDirectives = 0, duplicatesDropped = 1)
        // Osaka's row was never deleted (its key is still in the response) and the sync did not
        // throw -- it simply keeps its prior data, per Synced.duplicatesDropped's KDoc.
        profiles.profile(osaka.id) shouldNotBe null
    }

    @Test
    fun aFetchFailureLeavesStoredServersUntouched() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success("${link("Tokyo")}\n${link("Osaka")}", emptyMap())
        syncer().sync(id)

        response = FetchOutcome.Failed(FetchFailure.TimedOut)
        val result = syncer().sync(id)

        result shouldBe SyncResult.Failed(SubscriptionSyncFailure.TimedOut)
        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        profiles.observeGroups().first().single { it.id == groupId }.profiles.size shouldBe 2
    }

    @Test
    fun aFetchFailureIsRecordedOnTheSubscriptionRow() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success(link("Tokyo"), emptyMap())
        syncer().sync(id)
        val row = db.subscriptionDao().subscription(id) ?: error("missing test subscription")
        db.subscriptionDao().updateSubscription(row.copy(lastFetchedAt = 2L, lastAttemptedAt = 1L))
        response = FetchOutcome.Failed(FetchFailure.HwidRequired)

        syncer().sync(id)

        val stored = subscriptions.observeSubscriptions().first().single()
        stored.lastFetchStatus shouldBe "HwidRequired"
        stored.lastFetchedAt shouldBe 2L
        ((stored.lastAttemptedAt ?: 0L) > 1L) shouldBe true
        // §5.6: a closed vocabulary, never a raw message.
        stored.lastFetchDetail?.contains("http") shouldBe false
    }

    @Test
    fun aResponseThatParsesToZeroServersDoesNotEmptyTheGroup() = runTest {
        // Otherwise a provider's momentary template bug wipes the user's list.
        val id = addSubscription()
        response = FetchOutcome.Success(link("Tokyo"), emptyMap())
        syncer().sync(id)
        val row = db.subscriptionDao().subscription(id) ?: error("missing test subscription")
        db.subscriptionDao().updateSubscription(row.copy(lastFetchedAt = 2L, lastAttemptedAt = 1L))

        response = FetchOutcome.Success("not a subscription at all", emptyMap())
        val result = syncer().sync(id)

        (result is SyncResult.NoServers) shouldBe true
        val stored = subscriptions.observeSubscriptions().first().single()
        stored.lastFetchStatus shouldBe "NoServers"
        stored.lastFetchedAt shouldBe 2L
        ((stored.lastAttemptedAt ?: 0L) > 1L) shouldBe true
        val groupId = subscriptions.observeSubscriptions().first().single().groupId
        profiles.observeGroups().first().single { it.id == groupId }.profiles.size shouldBe 1
    }

    @Test
    fun rejectedDirectivesAreCountedButNotStored() = runTest {
        val id = addSubscription()
        response = FetchOutcome.Success(
            link("Tokyo"),
            mapOf("profile-update-interval" to "0", "totally-made-up" to "1"),
        )

        val result = syncer().sync(id)

        (result as SyncResult.Synced).rejectedDirectives shouldBe 2
        subscriptions.effective(id, "profile-update-interval", null).providerValue shouldBe null
    }
}
