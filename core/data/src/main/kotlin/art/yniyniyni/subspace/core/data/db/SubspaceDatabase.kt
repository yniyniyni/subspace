// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileGroupEntity::class,
        ProfileEntity::class,
        SettingEntity::class,
        SubscriptionEntity::class,
        SubscriptionDirectiveEntity::class,
        SubscriptionOverrideEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class SubspaceDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun settingDao(): SettingDao

    abstract fun subscriptionDao(): SubscriptionDao
}
