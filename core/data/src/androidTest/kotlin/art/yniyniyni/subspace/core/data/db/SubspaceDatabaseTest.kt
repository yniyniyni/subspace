// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

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
            db.profileDao().upsertProfile(sampleProfile(groupId, identityHash = "same"))

            db.profileDao().profileCount() shouldBe 1
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

    private fun sampleGroup(name: String = "Local configs") =
        ProfileGroupEntity(name = name, source = "MANUAL", position = 0, createdAt = 0L)

    private fun sampleProfile(
        groupId: Long,
        identityHash: String,
    ) = ProfileEntity(
        groupId = groupId,
        kind = "TYPED",
        identityHash = identityHash,
        name = "Test",
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
