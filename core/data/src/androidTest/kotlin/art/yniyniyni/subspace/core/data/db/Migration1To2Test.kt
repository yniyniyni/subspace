// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

private const val TEST_DB = "migration-test"

class Migration1To2Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SubspaceDatabase::class.java,
    )

    @Test
    fun migratesAV1DatabaseWithDataIntact() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'Local configs', 'MANUAL', 0, 100)",
            )
            db.execSQL(
                """
                INSERT INTO profiles
                  (id, groupId, kind, identityHash, name, protocol, address, port,
                   transport, outbound, rawJson, position, lastConnectedAt, lastError, createdAt)
                VALUES (1, 1, 'TYPED', 'hash-a', 'Server A', 'vless', 'example.com', 443,
                        'tcp · reality · 443', '{}', NULL, 0, 12345, NULL, 100)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT name, lastConnectedAt, subscriptionKey FROM profiles WHERE id = 1")
            .use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.getString(0) shouldBe "Server A"
                // The column every refresh must preserve — see spec §6.5.
                cursor.getLong(1) shouldBe 12345L
                // New column, NULL for every hand-imported row.
                cursor.isNull(2) shouldBe true
            }
    }

    @Test
    fun theNewTablesExistAfterMigration() {
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        listOf("subscriptions", "subscription_directives", "subscription_overrides")
            .forEach { table ->
                db.query(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table),
                ).use { it.count shouldBe 1 }
            }
    }

    @Test
    fun manyManualProfilesCoexistWithANullSubscriptionKey() {
        // SQLite treats NULLs as distinct in a UNIQUE index, which is what lets
        // one index serve both hand-imported and subscription-backed rows
        // (spec §4.2). If this fails, the index was declared without allowing it.
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'Local configs', 'MANUAL', 0, 100)",
            )
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        repeat(3) { i ->
            db.execSQL(
                """
                INSERT INTO profiles
                  (groupId, kind, identityHash, name, protocol, address, port, transport,
                   outbound, rawJson, position, lastConnectedAt, lastError, createdAt,
                   subscriptionKey)
                VALUES (1, 'TYPED', 'hash-$i', 'S$i', 'vless', 'h', 443, 'tcp',
                        '{}', NULL, $i, NULL, NULL, 100, NULL)
                """.trimIndent(),
            )
        }

        db.query("SELECT COUNT(*) FROM profiles").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 3
        }
    }

    @Test
    fun deletingASubscriptionCascadesToItsGroupAndProfiles() {
        // §A.1: "deletion must cascade". Enforced by the schema rather than by
        // remembering to do it.
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                "VALUES (7, 'Provider', 'SUBSCRIPTION', 0, 100)",
        )
        db.execSQL(
            "INSERT INTO subscriptions (id, groupId, url, userAgentOverride, hwidEnabled, " +
                "lastFetchedAt, lastFetchStatus, lastFetchDetail, createdAt) " +
                "VALUES (3, 7, 'https://example.com/s', NULL, 1, NULL, NULL, NULL, 100)",
        )
        db.execSQL(
            "INSERT INTO subscription_directives (subscriptionId, key, value, receivedAt) " +
                "VALUES (3, 'profile-title', 'Provider', 100)",
        )

        db.execSQL("DELETE FROM profile_groups WHERE id = 7")

        db.query("SELECT COUNT(*) FROM subscriptions").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
        db.query("SELECT COUNT(*) FROM subscription_directives").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
    }
}
