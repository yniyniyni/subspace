// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileGroupEntity::class, ProfileEntity::class, SettingEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class SubspaceDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun settingDao(): SettingDao
}
