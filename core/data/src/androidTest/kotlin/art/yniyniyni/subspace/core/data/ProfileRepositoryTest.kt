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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
    // running it: the collector saw zero emissions. Swapping in
    // UnconfinedTestDispatcher got emissions flowing but made the assertion
    // flaky (it could observe the group-created emission before the
    // profile-imported one). A real, time-bounded wait for the actual
    // post-import state is deterministic where virtual time is not: first{}
    // suspends on the real Flow until the predicate holds or the timeout
    // fires, so this fails loudly on a real regression instead of silently
    // passing on a stale read.
    // Block body, not `= runBlocking { ... }`: runBlocking<T> returns the
    // block's actual result type, and JUnit rejects a non-void test method —
    // unlike runTest, whose TestResult is a JVM-only typealias for Unit.
    @Test
    fun observeGroupsReEmitsAfterAnImport() {
        runBlocking {
            val groupId = repository.defaultGroupId()

            val afterImport =
                withTimeout(REEMIT_TIMEOUT_MS) {
                    val reemission =
                        async {
                            repository.observeGroups().first { groups ->
                                groups.singleOrNull()?.profiles?.isNotEmpty() == true
                            }
                        }
                    repository.import(listOf(sampleProfile()), groupId)
                    reemission.await()
                }

            afterImport.single().profiles.size shouldBe 1
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
