// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.db

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
 * Null on every migrated row, and that is not "eligible": it means "never
 * analysed". A row's verdict is decided by `analysePassthrough` plus a real
 * `testXray`, and neither can run inside a migration.
 */
internal val MIGRATION_4_5 = object : Migration(SCHEMA_VERSION_4, SCHEMA_VERSION_5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN passthroughRejection TEXT DEFAULT NULL")
    }
}
