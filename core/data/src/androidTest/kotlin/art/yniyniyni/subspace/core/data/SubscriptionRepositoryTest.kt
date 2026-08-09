// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubscriptionDirectiveEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SubspaceDatabase::class.java,
        ).build()
        repository = SubscriptionRepository(db.subscriptionDao(), ProfileRepository(db.profileDao()))
    }

    @After fun tearDown() = db.close()

    @Test
    fun addingASubscriptionCreatesASubscriptionSourcedGroup() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")

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
        val first = repository.add("https://example.com/sub", name = "Provider")
        val second = repository.add("https://example.com/sub", name = "Provider Again")

        second shouldBe first
        repository.observeSubscriptions().first().size shouldBe 1
    }

    @Test
    fun theProviderValueIsEffectiveWhenNothingIsPinned() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")
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
        val id = repository.add("https://example.com/sub", name = "Provider")
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
        val id = repository.add("https://example.com/sub", name = "Provider")
        repository.pin(id, "profile-update-interval", "24")

        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "1", 1)),
        )

        repository.effective(id, "profile-update-interval", "12").value shouldBe "24"
    }

    @Test
    fun unpinningFallsBackToTheProviderValue() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")
        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "6", 0)),
        )
        repository.pin(id, "profile-update-interval", "24")
        repository.unpin(id, "profile-update-interval")

        repository.effective(id, "profile-update-interval", "12").value shouldBe "6"
    }

    @Test
    fun theDefaultAppliesOnlyWhenThereIsNoProviderValueAndNoPin() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")

        val effective = repository.effective(id, "profile-update-interval", default = "12")

        effective.value shouldBe "12"
        effective.providerValue.shouldBeNull()
        effective.isPinned shouldBe false
    }

    @Test
    fun deletingASubscriptionRemovesItsGroupAndItsServers() = runTest {
        // §A.1: deletion must cascade.
        val id = repository.add("https://example.com/sub", name = "Provider")
        val groupId = repository.observeSubscriptions().first().single().groupId

        repository.delete(id)

        repository.observeSubscriptions().first().isEmpty() shouldBe true
        db.profileDao().observeGroups().first().none { it.id == groupId } shouldBe true
    }

    @Test
    fun theHwidTogglePersists() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")

        repository.setHwidEnabled(id, false)

        repository.observeSubscriptions().first().single().hwidEnabled shouldBe false
    }

    @Test
    fun refreshScheduleChangesReEmitWhenAnIntervalPinLands() {
        runBlocking {
            val id = repository.add("https://example.com/sub", name = "Provider")

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
            val id = repository.add("https://example.com/sub", name = "Provider")
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
}
