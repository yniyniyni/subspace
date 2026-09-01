// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_3 = 3

/**
 * M4's schema to M5's: routing rule sets and installed geo assets.
 *
 * A real migration rather than `fallbackToDestructiveMigration()`, for the reason
 * [MIGRATION_1_2] gives — a user's stored servers are the whole content of this
 * app. Version 2 **has** shipped (M4 merged as PR #6), so unlike the folding-in
 * trick that migration's comment describes, this must be a separate version.
 *
 * The SQL must match Room's generated schema **exactly**, including column order,
 * affinities and index names, or `runMigrationsAndValidate` fails. When it does,
 * read the expected shape out of the exported `core/data/schemas/…/3.json` rather
 * than guessing at the difference.
 */
internal val MIGRATION_2_3 = object : Migration(2, SCHEMA_VERSION_3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS routing_rule_sets (
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
                createdAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_routing_rule_sets_name " +
                "ON routing_rule_sets (name)",
        )

        // Task 8's table, created in the same migration: both belong to schema
        // version 3, and splitting them across two versions would mean shipping
        // a version nothing ever ran on.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS geo_assets (
                fileName TEXT NOT NULL PRIMARY KEY,
                sourceUrl TEXT NOT NULL,
                geoType TEXT NOT NULL,
                sha256 TEXT,
                sizeBytes INTEGER,
                installedAt INTEGER,
                lastAttemptedAt INTEGER,
                lastFailure TEXT
            )
            """.trimIndent(),
        )
    }
}
