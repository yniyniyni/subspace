// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_3 = 3
private const val SCHEMA_VERSION_4 = 4

/**
 * M5's schema to M6's: routing profile provenance on `routing_rule_sets`.
 *
 * A real migration rather than `fallbackToDestructiveMigration()`, for the reason
 * [MIGRATION_1_2] gives — a user's stored servers and rules are the whole content
 * of this app. Version 3 **has** shipped (M5 merged at `461e2bd`), so this must be
 * its own version.
 *
 * ## Why the table is rebuilt rather than `ALTER TABLE`d
 *
 * SQLite's `ALTER TABLE ADD COLUMN` cannot add a `REFERENCES` clause, and this
 * migration adds a foreign key to `subscriptions` with `ON DELETE CASCADE` —
 * §A.1's "deleting a subscription deletes its routing profiles". The twelve-step
 * create-copy-drop-rename is the documented way to change a table's constraints,
 * and it is what Room's own generated migrations do in the same situation.
 *
 * The SQL must match Room's generated schema **exactly**, including column order,
 * affinities and index names, or `runMigrationsAndValidate` fails. When it does,
 * read the expected shape out of the exported `core/data/schemas/…/4.json` rather
 * than guessing at the difference.
 */
internal val MIGRATION_3_4 = object : Migration(SCHEMA_VERSION_3, SCHEMA_VERSION_4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS routing_rule_sets_new (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                directSites TEXT NOT NULL,
                directIps TEXT NOT NULL,
                proxySites TEXT NOT NULL,
                proxyIps TEXT NOT NULL,
                blockSites TEXT NOT NULL,
                blockIps TEXT NOT NULL,
                routeOrder TEXT NOT NULL,
                domainStrategy TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                sourceKind TEXT,
                subscriptionId INTEGER,
                lastUpdated INTEGER,
                fingerprint TEXT,
                geoIpUrl TEXT,
                geoSiteUrl TEXT,
                globalProxy INTEGER,
                dnsJson TEXT,
                useChunkFiles INTEGER,
                assetGeneration INTEGER NOT NULL DEFAULT 0,
                assetState TEXT NOT NULL DEFAULT 'None',
                assetFailure TEXT,
                FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO routing_rule_sets_new
                (id, name, directSites, directIps, proxySites, proxyIps, blockSites, blockIps,
                 routeOrder, domainStrategy, createdAt)
            SELECT id, name, directSites, directIps, proxySites, proxyIps, blockSites, blockIps,
                   routeOrder, domainStrategy, createdAt
            FROM routing_rule_sets
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE routing_rule_sets")
        db.execSQL("ALTER TABLE routing_rule_sets_new RENAME TO routing_rule_sets")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_routing_rule_sets_name " +
                "ON routing_rule_sets (name)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_routing_rule_sets_subscriptionId " +
                "ON routing_rule_sets (subscriptionId)",
        )
    }
}
