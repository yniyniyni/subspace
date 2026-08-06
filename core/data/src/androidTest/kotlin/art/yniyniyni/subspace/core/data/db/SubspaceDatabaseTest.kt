// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

// Concurrent callers, not concurrent runs: enough parallel upserts on real
// threads (Dispatchers.IO) to make a read-then-write race land inside a
// single test invocation, given upsertProfile's read and write are two
// separate suspend calls without @Transaction serializing them.
private const val CONCURRENT_UPSERTS = 25

class SubspaceDatabaseTest {
    private lateinit var db: SubspaceDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    // Backtick names with spaces are avoided here (unlike the brief's literal
    // text) because kotlinx-coroutines-test's `runTest {}` lambda inherits the
    // enclosing test method's JVM name, and this module's minSdk (26, from
    // subspace.android.library) makes D8 emit a DEX version that rejects
    // spaces in synthetic class names — confirmed by a real build failure:
    // "Space characters in SimpleName ... are not allowed prior to DEX version
    // 040". Every other androidTest in this repo (e.g. XrayControllerTest)
    // already uses camelCase for the same reason.
    @Test
    fun deletingAGroupCascadesToItsProfiles() =
        runTest {
            val groupId = db.profileDao().insertGroup(sampleGroup())
            db.profileDao().insertProfile(sampleProfile(groupId, identityHash = "aaa"))
            db.profileDao().insertProfile(sampleProfile(groupId, identityHash = "bbb"))

            db.profileDao().deleteGroup(groupId)

            db.profileDao().profileCount() shouldBe 0
        }

    @Test
    fun theSameIdentityTwiceInOneGroupIsOneRow() =
        runTest {
            val groupId = db.profileDao().insertGroup(sampleGroup())

            db.profileDao().upsertProfile(sampleProfile(groupId, identityHash = "same"))
            db.profileDao().upsertProfile(sampleProfile(groupId, identityHash = "same", name = "Updated"))

            db.profileDao().profileCount() shouldBe 1
            // Not just "one row" — the second upsert's data must actually have
            // landed. A naive @Upsert can silently drop this write (fix round 1).
            db.profileDao().findProfile(groupId, identityHash = "same")?.name shouldBe "Updated"
        }

    @Test
    fun theSameIdentityInTwoGroupsIsTwoRows() =
        runTest {
            val a = db.profileDao().insertGroup(sampleGroup(name = "A"))
            val b = db.profileDao().insertGroup(sampleGroup(name = "B"))

            db.profileDao().upsertProfile(sampleProfile(a, identityHash = "same"))
            db.profileDao().upsertProfile(sampleProfile(b, identityHash = "same"))

            db.profileDao().profileCount() shouldBe 2
        }

    // upsertProfile is a read (findProfile) then a write (insert or update),
    // with no @Transaction the two coroutines can both observe "not found"
    // before either writes, and the second insert then throws
    // SQLiteConstraintException on the unique index instead of updating.
    // @Transaction serializes concurrent callers at the same
    // (groupId, identityHash) so exactly one row survives and nothing throws.
    @Test
    fun concurrentUpsertsOfTheSameIdentityProduceOneRowNotACrash() =
        runTest {
            val groupId = db.profileDao().insertGroup(sampleGroup())

            coroutineScope {
                (1..CONCURRENT_UPSERTS)
                    .map {
                        async(Dispatchers.IO) {
                            db.profileDao().upsertProfile(sampleProfile(groupId, identityHash = "race"))
                        }
                    }.awaitAll()
            }

            db.profileDao().profileCount() shouldBe 1
        }

    private fun sampleGroup(name: String = "Local configs") =
        ProfileGroupEntity(name = name, source = "MANUAL", position = 0, createdAt = 0L)

    private fun sampleProfile(
        groupId: Long,
        identityHash: String,
        name: String = "Test",
    ) = ProfileEntity(
        groupId = groupId,
        kind = "TYPED",
        identityHash = identityHash,
        name = name,
        protocol = "vless",
        address = "198.51.100.1",
        port = 443,
        transport = "tcp · reality",
        outbound = "{}",
        rawJson = null,
        position = 0,
        lastConnectedAt = null,
        lastError = null,
        createdAt = 0L,
    )
}
