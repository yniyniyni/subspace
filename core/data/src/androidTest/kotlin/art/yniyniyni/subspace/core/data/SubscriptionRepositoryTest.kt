// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubscriptionDirectiveEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

private const val REEMIT_TIMEOUT_MS = 5_000L

class SubscriptionRepositoryTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repository: SubscriptionRepository
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(
            context,
            SubspaceDatabase::class.java,
        ).build()
        val profiles = ProfileRepository(db.profileDao())
        val settings = SettingsRepository(db.settingDao()) { "test-hwid" }
        root = java.io.File(context.cacheDir, "subscription-repository-${System.nanoTime()}").apply { mkdirs() }
        val deletion =
            RoutingProfileDeletion(
                db,
                RoutingRepository(db.routingRuleSetDao()),
                RuleSetAssets(root),
                settings,
                profiles,
            )
        repository = SubscriptionRepository(db.subscriptionDao(), profiles, db, deletion)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    /**
     * `add` used to commit the group and the subscription as two independent writes, so a failure
     * or a cancellation between them left a `source = "SUBSCRIPTION"` group with no subscription:
     * a row the Servers screen lists but which cannot be refreshed, opened or deleted as a
     * subscription. The foreign key cannot undo it — it cascades group -> subscription, never the
     * other way.
     *
     * The failure is a real foreign-key violation on the real schema rather than a mock, so what
     * rolls back is genuine SQLite rollback and not a stubbed approximation of it.
     *
     * Honest about what this does and does not cover: it drives the transaction directly rather
     * than through `add`, because `add`'s own failure window is not reachable from a test. The
     * URL lookup returns early for a duplicate and the `Mutex` serialises the callers who could
     * otherwise race into the unique index, so there is no input that makes `add` fail *between*
     * its two writes. What is asserted is the property `add` now depends on — a group inserted
     * earlier in the transaction does not survive a later failure in it — plus, in the sibling
     * test below, that concurrent `add`s really do produce one subscription and one group.
     */
    @Test
    fun aFailedSubscriptionInsertLeavesNoOrphanGroup() = runTest {
        val groupsBefore = db.profileDao().observeGroups().first().size

        // The group insert succeeds and the subscription insert then violates its foreign key, in
        // one transaction — the exact shape `add` performs. Room's own rollback is what has to
        // take the group with it.
        runCatching {
            db.withTransaction {
                ProfileRepository(db.profileDao()).createGroup("Doomed", source = "SUBSCRIPTION")
                db.subscriptionDao().insertSubscription(
                    SubscriptionEntity(
                        groupId = 999_999L, // no such group — FK violation
                        url = "https://example.com/sub",
                        userAgentOverride = null,
                        hwidEnabled = true,
                        lastFetchedAt = null,
                        lastAttemptedAt = null,
                        lastFetchStatus = null,
                        lastFetchDetail = null,
                        createdAt = 0L,
                    ),
                )
            }
        }.isFailure shouldBe true

        val groups = db.profileDao().observeGroups().first()
        groups.none { it.name == "Doomed" } shouldBe true
        groups.size shouldBe groupsBefore
        repository.observeSubscriptions().first().shouldBeEmpty()
    }

    @Test
    fun addingTheSameUrlConcurrentlyCreatesExactlyOneSubscriptionAndOneGroup() = runTest {
        val url = "https://example.com/sub"

        val results =
            (1..8).map { async { repository.add(url, name = "Provider $it") } }.awaitAll()

        results.map { it.id }.distinct().size shouldBe 1
        results.count { it.created } shouldBe 1
        repository.observeSubscriptions().first().size shouldBe 1
        db.profileDao().observeGroups().first().count { it.source == "SUBSCRIPTION" } shouldBe 1
    }

    @Test
    fun addingASubscriptionCreatesASubscriptionSourcedGroup() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id

        val stored = repository.observeSubscriptions().first().single()
        stored.id shouldBe id
        stored.hwidEnabled shouldBe true // §A.4.1: on by default

        // The M4 seam ProfileGroupEntity was built with: a subscription-backed
        // group is an ordinary profile_groups row with source = "SUBSCRIPTION",
        // not a special case the Servers screen has to know about separately.
        val group = db.profileDao().observeGroups().first().single { it.id == stored.groupId }
        group.source shouldBe "SUBSCRIPTION"
    }

    @Test
    fun addingTheSameUrlTwiceReturnsTheExistingSubscription() = runTest {
        val first = repository.add("https://example.com/sub", name = "Provider").id
        val second = repository.add("https://example.com/sub", name = "Provider Again").id

        second shouldBe first
        repository.observeSubscriptions().first().size shouldBe 1
    }

    @Test
    fun theProviderValueIsEffectiveWhenNothingIsPinned() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id
        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "6", 0)),
        )

        val effective = repository.effective(id, "profile-update-interval", default = "12")

        effective.value shouldBe "6"
        effective.providerValue shouldBe "6"
        effective.isPinned shouldBe false
    }

    @Test
    fun aPinBeatsTheProviderValueAndBothStayReadable() = runTest {
        // Spec D3's UI consequence: "provider suggests 6 h, you pinned 24 h"
        // must be renderable, or a pinned field looks like a subscription that
        // stopped updating.
        val id = repository.add("https://example.com/sub", name = "Provider").id
        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "6", 0)),
        )
        repository.pin(id, "profile-update-interval", "24")

        val effective = repository.effective(id, "profile-update-interval", default = "12")

        effective.value shouldBe "24"
        effective.providerValue shouldBe "6"
        effective.isPinned shouldBe true
    }

    @Test
    fun aPinSurvivesADirectiveUpdateCarryingADifferentValue() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id
        repository.pin(id, "profile-update-interval", "24")

        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "1", 1)),
        )

        repository.effective(id, "profile-update-interval", "12").value shouldBe "24"
    }

    @Test
    fun unpinningFallsBackToTheProviderValue() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id
        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "6", 0)),
        )
        repository.pin(id, "profile-update-interval", "24")
        repository.unpin(id, "profile-update-interval")

        repository.effective(id, "profile-update-interval", "12").value shouldBe "6"
    }

    @Test
    fun theDefaultAppliesOnlyWhenThereIsNoProviderValueAndNoPin() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id

        val effective = repository.effective(id, "profile-update-interval", default = "12")

        effective.value shouldBe "12"
        effective.providerValue.shouldBeNull()
        effective.isPinned shouldBe false
    }

    @Test
    fun deletingASubscriptionRemovesItsGroupAndItsServers() = runTest {
        // §A.1: deletion must cascade.
        val id = repository.add("https://example.com/sub", name = "Provider").id
        val groupId = repository.observeSubscriptions().first().single().groupId

        repository.delete(id)

        repository.observeSubscriptions().first().isEmpty() shouldBe true
        db.profileDao().observeGroups().first().none { it.id == groupId } shouldBe true
    }

    @Test
    fun theHwidTogglePersists() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id

        repository.setHwidEnabled(id, false)

        repository.observeSubscriptions().first().single().hwidEnabled shouldBe false
    }

    @Test
    fun hwidSetterPreservesSyncStatusThatLandsInsideItsWriteWindow() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id
        installSyncDuringUserWriteTrigger(column = "hwidEnabled", status = "SyncedAfterRead")

        repository.setHwidEnabled(id, false)

        val stored = repository.observeSubscriptions().first().single()
        stored.hwidEnabled shouldBe false
        stored.lastFetchStatus shouldBe "SyncedAfterRead"
    }

    @Test
    fun userAgentSetterPreservesSyncStatusThatLandsInsideItsWriteWindow() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id
        installSyncDuringUserWriteTrigger(column = "userAgentOverride", status = "SyncedAfterRead")

        repository.setUserAgentOverride(id, "Subspace/Test")

        val stored = repository.observeSubscriptions().first().single()
        stored.userAgentOverride shouldBe "Subspace/Test"
        stored.lastFetchStatus shouldBe "SyncedAfterRead"
    }

    @Test
    fun syncStatusWrittenAfterUserSettersPreservesBothUserValues() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider").id

        repository.setHwidEnabled(id, false)
        repository.setUserAgentOverride(id, "Subspace/Test")
        db.subscriptionDao().recordFetchResult(id, at = 42L, status = "NoServers", detail = "NoServers")

        val stored = repository.observeSubscriptions().first().single()
        stored.hwidEnabled shouldBe false
        stored.userAgentOverride shouldBe "Subspace/Test"
        stored.lastFetchStatus shouldBe "NoServers"
    }

    @Test
    fun userSettersAreNoOpsWhenTheSubscriptionIsMissing() = runTest {
        repository.setHwidEnabled(id = 404L, enabled = false)
        repository.setUserAgentOverride(id = 404L, userAgent = "Subspace/Test")

        repository.observeSubscriptions().first().shouldBeEmpty()
    }

    @Test
    fun refreshScheduleChangesReEmitWhenAnIntervalPinLands() {
        runBlocking {
            val id = repository.add("https://example.com/sub", name = "Provider").id

            withTimeout(REEMIT_TIMEOUT_MS) {
                val emissions = Channel<Unit>(Channel.UNLIMITED)
                val collector =
                    launch {
                        repository.observeRefreshScheduleChanges().collect { emissions.send(it) }
                    }

                // Initial Room snapshot: one subscription, no directives or pins yet.
                emissions.receive()
                repository.pin(id, "profile-update-interval", "1")

                // The app-level RefreshScheduler collector receives this and replaces its unique
                // one-shot work with the newly earliest due time.
                emissions.receive()
                collector.cancel()
            }
        }
    }

    // Review finding (Task 10): observeEffective's own resolution logic is
    // exercised transitively via effective()'s tests above (they share
    // resolveEffective), but the combine() wiring — does landing a pin
    // actually cause a *re-emission* — was untested. A Flow that resolves
    // correctly once but never re-emits looks identical to a working screen
    // until the moment a user pins something, which is exactly the scenario
    // Tasks 14-15 bind to.
    //
    // Same shape as ProfileRepositoryTest.observeGroupsReEmitsAfterAnImport,
    // for the same two reasons documented there: runTest + advanceUntilIdle()
    // cannot observe Room's real invalidation (it hops onto a genuine
    // background query executor, not virtual time), and this test's name is
    // plain camelCase with a runBlocking block body rather than a backtick
    // name under runTest{}, because minSdk 26 makes D8 reject a space in the
    // synthetic class name runTest{}'s lambda would otherwise inherit from
    // this method.
    @Test
    fun observeEffectiveReEmitsWhenAPinLands() {
        runBlocking {
            val id = repository.add("https://example.com/sub", name = "Provider").id
            db.subscriptionDao().putDirectives(
                listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "6", 0)),
            )

            withTimeout(REEMIT_TIMEOUT_MS) {
                val emissions = Channel<EffectiveValue>(Channel.UNLIMITED)
                val collector =
                    launch {
                        repository.observeEffective(id, "profile-update-interval", default = "12")
                            .collect { emissions.send(it) }
                    }

                // Checkpoint 1: the collector's own initial query, observed
                // before pin() is called at all — provider wins, nothing pinned.
                val beforePin = emissions.receive()
                beforePin.value shouldBe "6"
                beforePin.isPinned shouldBe false

                repository.pin(id, "profile-update-interval", "24")

                // Checkpoint 2: skip past any emission that doesn't yet carry
                // the pin (Room may re-signal more than once), but require at
                // least one further, distinct emission to arrive.
                var afterPin = emissions.receive()
                while (!afterPin.isPinned) {
                    afterPin = emissions.receive()
                }

                afterPin.value shouldBe "24"
                afterPin.providerValue shouldBe "6"
                collector.cancel()
            }
        }
    }

    /**
     * Simulates the interleaving a stale read-then-full-row setter otherwise makes possible:
     * SQLite runs this trigger after the setter has captured its arguments but before its UPDATE
     * lands. A targeted column UPDATE leaves the status written here alone; Room's generated
     * full-row `@Update` writes the captured null status back over it.
     */
    private fun installSyncDuringUserWriteTrigger(
        column: String,
        status: String,
    ) {
        require(column == "hwidEnabled" || column == "userAgentOverride")
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER sync_during_user_write
            BEFORE UPDATE OF $column ON subscriptions
            BEGIN
                UPDATE subscriptions
                SET lastAttemptedAt = 41, lastFetchedAt = 42,
                    lastFetchStatus = '$status', lastFetchDetail = '$status'
                WHERE id = OLD.id;
            END
            """.trimIndent(),
        )
    }
}
