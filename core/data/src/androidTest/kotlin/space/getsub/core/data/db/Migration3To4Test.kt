// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

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
                  (id, name, directSites, directIps, proxySites, proxyIps, blockSites, blockIps,
                   routeOrder, domainStrategy, createdAt)
                VALUES (73, 'Handmade distinct', 'domain:direct.example', '10.10.0.0/16',
                        'geosite:proxy-test', 'geoip:proxy-test', 'regexp:^blocked.example$',
                        '203.0.113.9/32', 'DIRECT,BLOCK,PROXY', 'AS_IS', 1700000123)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        db.query(
            """
            SELECT id, name, directSites, directIps, proxySites, proxyIps, blockSites, blockIps,
                   routeOrder, domainStrategy, createdAt, sourceKind, subscriptionId, lastUpdated,
                   fingerprint, geoIpUrl, geoSiteUrl, globalProxy, dnsJson, useChunkFiles,
                   assetGeneration, assetState, assetFailure
            FROM routing_rule_sets
            """.trimIndent(),
        )
            .use { cursor ->
                cursor.moveToFirst() shouldBe true
                // Every v3 value must survive the table rebuild, not merely the schema shape.
                cursor.getLong(0) shouldBe 73L
                cursor.getString(1) shouldBe "Handmade distinct"
                cursor.getString(2) shouldBe "domain:direct.example"
                cursor.getString(3) shouldBe "10.10.0.0/16"
                cursor.getString(4) shouldBe "geosite:proxy-test"
                cursor.getString(5) shouldBe "geoip:proxy-test"
                cursor.getString(6) shouldBe "regexp:^blocked.example$"
                cursor.getString(7) shouldBe "203.0.113.9/32"
                cursor.getString(8) shouldBe "DIRECT,BLOCK,PROXY"
                cursor.getString(9) shouldBe "AS_IS"
                cursor.getLong(10) shouldBe 1_700_000_123L

                // A pre-existing row is hand-made: all provenance is absent/defaulted.
                cursor.isNull(11) shouldBe true // sourceKind
                cursor.isNull(12) shouldBe true // subscriptionId
                cursor.isNull(13) shouldBe true // lastUpdated
                cursor.isNull(14) shouldBe true // fingerprint
                cursor.isNull(15) shouldBe true // geoIpUrl
                cursor.isNull(16) shouldBe true // geoSiteUrl
                cursor.isNull(17) shouldBe true // globalProxy
                cursor.isNull(18) shouldBe true // dnsJson
                cursor.isNull(19) shouldBe true // useChunkFiles
                cursor.getLong(20) shouldBe 0L // assetGeneration
                cursor.getString(21) shouldBe "None" // assetState
                cursor.isNull(22) shouldBe true // assetFailure
            }
    }
}
