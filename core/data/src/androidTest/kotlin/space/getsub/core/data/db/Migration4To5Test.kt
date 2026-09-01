// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4To5Test {
    private companion object {
        const val DB_NAME = "migration-4-5-test"
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            SubspaceDatabase::class.java,
        )

    @Test
    fun addsPassthroughRejectionAndPreservesRows() {
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO profile_groups (id, name, source, position, createdAt) " +
                    "VALUES (1, 'g', 'MANUAL', 0, 0)",
            )
            db.execSQL(
                """
                INSERT INTO profiles (id, groupId, kind, identityHash, name, protocol, address, port,
                                      transport, outbound, rawJson, position, lastConnectedAt,
                                      lastError, createdAt)
                VALUES (1, 1, 'RAW_JSON', 'h1', 'pasted', 'vless', '192.0.2.1', 443,
                        'tcp', '{}', '{"outbounds":[]}', 0, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO profiles (id, groupId, kind, identityHash, name, protocol, address, port,
                                      transport, outbound, rawJson, position, lastConnectedAt,
                                      lastError, createdAt)
                VALUES (2, 1, 'TYPED', 'h2', 'typed', 'vless', '192.0.2.2', 443,
                        'tcp', '{}', NULL, 1, NULL, NULL, 0)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(DB_NAME, 5, true, MIGRATION_4_5)

        db.query("SELECT rawJson, passthroughRejection FROM profiles WHERE id = 1").use { cursor ->
            cursor.moveToFirst() shouldBe true
            cursor.getString(0) shouldBe """{"outbounds":[]}"""
            // Existing rows have never been analysed; null means eligible to
            // run as written, which is a claim this migration cannot make.
            cursor.getString(1) shouldBe "Unvalidated"
        }
        db.query("SELECT passthroughRejection FROM profiles WHERE id = 2").use { cursor ->
            cursor.moveToFirst() shouldBe true
            cursor.isNull(0) shouldBe true
        }
    }
}
