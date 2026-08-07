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
        db.execSQL("ALTER TABLE profiles ADD COLUMN subscriptionKey TEXT DEFAULT NULL")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_profiles_groupId_subscriptionKey " +
                "ON profiles (groupId, subscriptionKey)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subscriptions (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                groupId INTEGER NOT NULL,
                url TEXT NOT NULL,
                userAgentOverride TEXT,
                hwidEnabled INTEGER NOT NULL,
                lastFetchedAt INTEGER,
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
