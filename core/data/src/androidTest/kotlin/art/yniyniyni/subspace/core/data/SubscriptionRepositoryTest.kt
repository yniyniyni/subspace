// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubscriptionDirectiveEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

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
    fun `adding a subscription creates a SUBSCRIPTION-sourced group`() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")

        val stored = repository.observeSubscriptions().first().single()
        stored.id shouldBe id
        stored.hwidEnabled shouldBe true // §A.4.1: on by default
    }

    @Test
    fun `adding the same url twice returns the existing subscription`() = runTest {
        val first = repository.add("https://example.com/sub", name = "Provider")
        val second = repository.add("https://example.com/sub", name = "Provider Again")

        second shouldBe first
        repository.observeSubscriptions().first().size shouldBe 1
    }

    @Test
    fun `the provider value is effective when nothing is pinned`() = runTest {
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
    fun `a pin beats the provider value and both stay readable`() = runTest {
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
    fun `a pin survives a directive update carrying a different value`() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")
        repository.pin(id, "profile-update-interval", "24")

        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "1", 1)),
        )

        repository.effective(id, "profile-update-interval", "12").value shouldBe "24"
    }

    @Test
    fun `unpinning falls back to the provider value`() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")
        db.subscriptionDao().putDirectives(
            listOf(SubscriptionDirectiveEntity(id, "profile-update-interval", "6", 0)),
        )
        repository.pin(id, "profile-update-interval", "24")
        repository.unpin(id, "profile-update-interval")

        repository.effective(id, "profile-update-interval", "12").value shouldBe "6"
    }

    @Test
    fun `the default applies only when there is no provider value and no pin`() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")

        val effective = repository.effective(id, "profile-update-interval", default = "12")

        effective.value shouldBe "12"
        effective.providerValue.shouldBeNull()
        effective.isPinned shouldBe false
    }

    @Test
    fun `deleting a subscription removes its group and its servers`() = runTest {
        // §A.1: deletion must cascade.
        val id = repository.add("https://example.com/sub", name = "Provider")
        val groupId = repository.observeSubscriptions().first().single().groupId

        repository.delete(id)

        repository.observeSubscriptions().first().isEmpty() shouldBe true
        db.profileDao().observeGroups().first().none { it.id == groupId } shouldBe true
    }

    @Test
    fun `the hwid toggle persists`() = runTest {
        val id = repository.add("https://example.com/sub", name = "Provider")

        repository.setHwidEnabled(id, false)

        repository.observeSubscriptions().first().single().hwidEnabled shouldBe false
    }
}
