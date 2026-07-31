// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
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

// Backtick names with spaces are avoided here for the same reason
// SubspaceDatabaseTest avoids them: runTest {}'s lambda inherits the
// enclosing test method's JVM name, and this module's minSdk 26 makes D8
// reject spaces in the resulting synthetic class name below DEX 040.
class ProfileRepositoryTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        repository = ProfileRepository(db.profileDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun importingTwoProfilesIntoTheDefaultGroupYieldsTwoRows() =
        runTest {
            val groupId = repository.defaultGroupId()

            repository.import(
                listOf(sampleProfile(address = "198.51.100.1"), sampleProfile(address = "198.51.100.2")),
                groupId,
            )

            repository.observeGroups().first().single().profiles.size shouldBe 2
        }

    @Test
    fun importingTheSameProfileTwiceYieldsOneRow() =
        runTest {
            val groupId = repository.defaultGroupId()

            repository.import(listOf(sampleProfile()), groupId)
            repository.import(listOf(sampleProfile()), groupId)

            repository.observeGroups().first().single().profiles.size shouldBe 1
        }

    @Test
    fun deletingAGroupRemovesItsProfiles() =
        runTest {
            val groupId = repository.defaultGroupId()
            repository.import(listOf(sampleProfile()), groupId)

            repository.deleteGroup(groupId)

            repository.observeGroups().first() shouldBe emptyList()
        }

    // Deliberately not runTest + advanceUntilIdle() here, despite that being
    // the brief's literal example. Room's Flow re-queries by hopping onto its
    // own real query executor (a genuine withContext to a background thread,
    // not a virtual-time suspension), so a StandardTestDispatcher launch{}
    // collecting it is never woken by advanceUntilIdle() — confirmed by
    // running it: the collector saw zero emissions.
    //
    // Fix round 1 (code review of 11d5435): the first replacement used
    // Flow.first { predicate } from a freshly-subscribed collector racing
    // import() on real executors. first{} is satisfied by *whichever*
    // emission matches the predicate — nothing required a second, later
    // emission after an empty first one, so that version could pass even if
    // invalidation never fired at all, as long as the collector's own
    // initial query happened to run after the write had already committed.
    // withTimeout bounded how long the test waited; it did not bound what
    // the test proved.
    //
    // Fixed with two explicit checkpoints instead of one order-agnostic
    // predicate, using a Channel so each checkpoint is a real
    // synchronization point (send-then-receive), not a hope about ordering:
    //   1. Receive and assert the pre-import emission is empty. Room's Flow
    //      registers its invalidation observer before running its initial
    //      query (it has to, to avoid missing a write that lands mid-query),
    //      so by the time this receive() returns, the collector is
    //      guaranteed to be watching for writes to the profiles table.
    //   2. Only then call import().
    //   3. Receive again and require a *distinct* later emission whose
    //      profiles are non-empty. That emission can only exist if the
    //      write actually invalidated the collector's query — this is the
    //      one property Flow.first{} above did not require.
    // A broken invalidation path makes step 3 hang until the timeout fires
    // instead of silently passing, because no second emission ever arrives.
    // Block body, not `= runBlocking { ... }`: runBlocking<T> returns the
    // block's actual result type, and JUnit rejects a non-void test method —
    // unlike runTest, whose TestResult is a JVM-only typealias for Unit.
    @Test
    fun observeGroupsReEmitsAfterAnImport() {
        runBlocking {
            val groupId = repository.defaultGroupId()

            withTimeout(REEMIT_TIMEOUT_MS) {
                val emissions = Channel<List<ProfileGroup>>(Channel.UNLIMITED)
                val collector = launch { repository.observeGroups().collect { emissions.send(it) } }

                // Checkpoint 1: the collector's own initial query, observed
                // before import() is called at all.
                emissions.receive().single().profiles shouldBe emptyList()

                repository.import(listOf(sampleProfile()), groupId)

                // Checkpoint 2: skip past any emission that doesn't yet carry
                // the imported row (Room may re-signal more than once), but
                // require at least one further, distinct emission to arrive.
                var afterImport = emissions.receive()
                while (afterImport.single().profiles.isEmpty()) {
                    afterImport = emissions.receive()
                }

                afterImport.single().profiles.size shouldBe 1
                collector.cancel()
            }
        }
    }

    private fun sampleProfile(address: String = "198.51.100.1") =
        Profile(
            id = "unused",
            name = "Test",
            outbound =
            VlessOutbound(
                address = address,
                port = 443,
                uuid = "1e0f2a2e-6b2b-4b9a-9a3b-000000000000",
                flow = null,
                stream = StreamSettings(network = "tcp", security = Security.None),
            ),
        )
}
