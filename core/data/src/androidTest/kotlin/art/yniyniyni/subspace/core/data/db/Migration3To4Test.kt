// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

private const val TEST_DB = "migration-3-to-4-test"

class Migration3To4Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SubspaceDatabase::class.java,
    )

    @Test
    fun migrates3To4PreservingRuleSets() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO routing_rule_sets
                  (name, directSites, directIps, proxySites, proxyIps, blockSites, blockIps,
                   routeOrder, domainStrategy, createdAt)
                VALUES ('Handmade', '', '10.0.0.0/8', 'geosite:cn', '', '', '',
                        'BLOCK,PROXY,DIRECT', 'IP_IF_NON_MATCH', 1700000000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query("SELECT name, proxySites, sourceKind, assetGeneration, assetState FROM routing_rule_sets")
            .use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.getString(0) shouldBe "Handmade"
                cursor.getString(1) shouldBe "geosite:cn"
                // A pre-existing row is a hand-made set: no provenance, no generation.
                cursor.isNull(2) shouldBe true
                cursor.getLong(3) shouldBe 0L
                cursor.getString(4) shouldBe "None"
            }
    }
}
