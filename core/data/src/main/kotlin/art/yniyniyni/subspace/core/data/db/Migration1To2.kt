// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * M3's schema to M4's.
 *
 * A real migration rather than `fallbackToDestructiveMigration()`: a user's
 * stored servers are the whole content of this app, and dropping them on an
 * upgrade is not a recoverable mistake.
 *
 * The SQL below must match Room's generated schema **exactly**, including column
 * order, affinities and index names, or `runMigrationsAndValidate` fails. When
 * it does, read the expected shape out of the exported
 * `core/data/schemas/…/2.json` rather than guessing at the difference.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // "DEFAULT NULL" here is redundant with SQLite's own default for a
        // nullable column and does not appear in the exported 2.json's
        // `defaultValue` for this field (Room only emits a `defaultValue` for
        // an explicit non-null default on the entity, e.g. via @ColumnInfo).
        // That asymmetry is safe, not a bug: Room's TableInfo.Column.equals
        // only compares defaultValue when the *entity-derived* side is
        // non-null, so a live column with no recorded default validates fine
        // against an entity that also has none. Do NOT "fix" this by adding
        // `@ColumnInfo(defaultValue = "NULL")` to ProfileEntity.subscriptionKey
        // to make the SQL and the entity match textually — that would give the
        // entity side a non-null defaultValue string, and validation would
        // then require every already-migrated v1-to-v2 database's live column
        // to carry a recorded default it does not have, failing
        // runMigrationsAndValidate (and, worse, every real user's first
        // launch on v2) with an IllegalStateException.
        db.execSQL("ALTER TABLE profiles ADD COLUMN subscriptionKey TEXT DEFAULT NULL")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_profiles_groupId_subscriptionKey " +
                "ON profiles (groupId, subscriptionKey)",
        )
        // Task 11 review fix: added directly to this same v1-to-v2 migration rather than as a
        // v3 migration of its own. Version 2 has not shipped — it exists only on this
        // unreleased branch — so there is no installed database with a v2 schema that a v3
        // migration would need to carry forward; folding the column in here is strictly
        // simpler and the schema JSON is re-exported to match.
        db.execSQL("ALTER TABLE profiles ADD COLUMN droppedFromSubscriptionAt INTEGER DEFAULT NULL")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subscriptions (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                groupId INTEGER NOT NULL,
                url TEXT NOT NULL,
                userAgentOverride TEXT,
                hwidEnabled INTEGER NOT NULL,
                lastFetchedAt INTEGER,
                lastAttemptedAt INTEGER,
                lastFetchStatus TEXT,
                lastFetchDetail TEXT,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(groupId) REFERENCES profile_groups(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_subscriptions_url ON subscriptions (url)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_subscriptions_groupId ON subscriptions (groupId)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subscription_directives (
                subscriptionId INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                receivedAt INTEGER NOT NULL,
                PRIMARY KEY(subscriptionId, key),
                FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subscription_overrides (
                subscriptionId INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                pinnedAt INTEGER NOT NULL,
                PRIMARY KEY(subscriptionId, key),
                FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}
