// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Data access for the three subscription tables. */
@Suppress("TooManyFunctions") // A DAO's surface is the width of its tables' usage, per ProfileDao.
@Dao
internal interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun subscription(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url")
    suspend fun subscriptionByUrl(url: String): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions")
    suspend fun allSubscriptions(): List<SubscriptionEntity>

    @Insert
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: Long)

    @Query("SELECT * FROM subscription_directives WHERE subscriptionId = :id")
    fun observeDirectives(id: Long): Flow<List<SubscriptionDirectiveEntity>>

    @Query("SELECT * FROM subscription_directives WHERE subscriptionId = :id")
    suspend fun directives(id: Long): List<SubscriptionDirectiveEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDirectives(directives: List<SubscriptionDirectiveEntity>)

    /**
     * Removes directives the provider no longer sends.
     *
     * A key vanishing from a response means the provider stopped asserting it,
     * and leaving the old value would keep applying an instruction that was
     * withdrawn. Overrides are untouched by this — a pin is the user's, not the
     * provider's, and survives (spec D3).
     */
    @Query("DELETE FROM subscription_directives WHERE subscriptionId = :id AND key NOT IN (:keep)")
    suspend fun pruneDirectives(id: Long, keep: List<String>)

    @Query("DELETE FROM subscription_directives WHERE subscriptionId = :id")
    suspend fun clearDirectives(id: Long)

    @Query("SELECT * FROM subscription_overrides WHERE subscriptionId = :id")
    fun observeOverrides(id: Long): Flow<List<SubscriptionOverrideEntity>>

    @Query("SELECT * FROM subscription_overrides WHERE subscriptionId = :id")
    suspend fun overrides(id: Long): List<SubscriptionOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOverride(override: SubscriptionOverrideEntity)

    @Query("DELETE FROM subscription_overrides WHERE subscriptionId = :id AND key = :key")
    suspend fun deleteOverride(id: Long, key: String)

    @Query("SELECT * FROM profiles WHERE groupId = :groupId AND subscriptionKey IS NOT NULL")
    suspend fun subscriptionProfiles(groupId: Long): List<ProfileEntity>
}
