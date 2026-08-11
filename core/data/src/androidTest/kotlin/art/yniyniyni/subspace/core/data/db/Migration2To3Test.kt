// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

private const val TEST_DB = "migration-2-to-3-test"

class Migration2To3Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SubspaceDatabase::class.java,
    )

    @Test
    fun migratesAV2DatabaseWithUserDataIntact() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'Local configs', 'MANUAL', 0, 100)",
            )
            db.execSQL(
                """
                INSERT INTO profiles
                  (id, groupId, kind, identityHash, name, protocol, address, port,
                   transport, outbound, rawJson, position, lastConnectedAt, lastError, createdAt,
                   subscriptionKey, droppedFromSubscriptionAt)
                VALUES (1, 1, 'TYPED', 'hash-a', 'Server A', 'vless', 'example.com', 443,
                        'tcp', '{}', NULL, 0, 12345, NULL, 100, NULL, NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        db.query("SELECT name, address, lastConnectedAt FROM profiles WHERE id = 1").use { cursor ->
            cursor.moveToFirst() shouldBe true
            cursor.getString(0) shouldBe "Server A"
            cursor.getString(1) shouldBe "example.com"
            cursor.getLong(2) shouldBe 12345L
        }
        listOf("routing_rule_sets", "geo_assets").forEach { table ->
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table),
            ).use { it.count shouldBe 1 }
        }
    }
}
