// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

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
        RoutingRuleSetEntity::class,
        GeoAssetEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
internal abstract class SubspaceDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    abstract fun settingDao(): SettingDao

    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun routingRuleSetDao(): RoutingRuleSetDao

    abstract fun geoAssetDao(): GeoAssetDao
}
