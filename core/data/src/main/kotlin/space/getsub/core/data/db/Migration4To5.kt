// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val SCHEMA_VERSION_4 = 4
private const val SCHEMA_VERSION_5 = 5

/**
 * M6.5's schema to M7's: the passthrough eligibility verdict on `profiles`.
 *
 * A plain `ADD COLUMN`, unlike [MIGRATION_3_4]'s rebuild — the column is
 * nullable and carries no `REFERENCES` clause, which is the only thing SQLite's
 * `ALTER TABLE` cannot express.
 *
 * Existing raw rows are marked `Unvalidated`, not left null: null is the
 * positive "eligible" verdict for a newly imported row, while a migration
 * cannot run `analysePassthrough` plus a real `testXray`. Typed rows keep null
 * because passthrough eligibility is meaningless for them.
 */
internal val MIGRATION_4_5 = object : Migration(SCHEMA_VERSION_4, SCHEMA_VERSION_5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN passthroughRejection TEXT DEFAULT NULL")
        db.execSQL(
            "UPDATE profiles SET passthroughRejection = 'Unvalidated' WHERE kind = 'RAW_JSON'",
        )
    }
}
