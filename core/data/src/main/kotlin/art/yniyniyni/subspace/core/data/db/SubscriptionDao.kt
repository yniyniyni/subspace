// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// Guaranteed to collide with no real identityHash (those are 64-char lowercase
// hex SHA-256 digests, see IdentityHash.kt) and, being suffixed with a row id,
// guaranteed distinct from every other row's placeholder too. See
// upsertBySubscriptionKey's KDoc for what this placeholder is for.
private const val TEMP_IDENTITY_HASH_PREFIX = "subspace-sync-temp:"

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

    /** Overwrites [groupId]'s display name. Used for the `profile-title` directive (§A.3.2). */
    @Query("UPDATE profile_groups SET name = :name WHERE id = :groupId")
    suspend fun renameGroup(groupId: Long, name: String)

    /**
     * Deletes a single profile by id.
     *
     * A second `profiles` delete-by-id, alongside [ProfileDao.deleteProfile]: kept here rather
     * than reached through the other DAO because [applySync]'s `@Transaction` needs every
     * statement it calls to live on `this` DAO (Room resolves a `@Transaction` default method's
     * calls through its own generated implementation).
     */
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    /** Looks up a profile by its (groupId, subscriptionKey) slot — see [ProfileEntity]'s unique index. */
    @Query("SELECT * FROM profiles WHERE groupId = :groupId AND subscriptionKey = :subscriptionKey")
    suspend fun findBySubscriptionKey(groupId: Long, subscriptionKey: String): ProfileEntity?

    /**
     * Neutralises [id]'s `identityHash` to a placeholder no real hash can ever equal.
     *
     * See [upsertBySubscriptionKey]'s KDoc for what this is for: it is called on every row
     * about to be updated, for every row in one sync's batch, **before** any of them receives
     * its final `identityHash` — never as part of a single row's own upsert.
     */
    @Query("UPDATE profiles SET identityHash = '$TEMP_IDENTITY_HASH_PREFIX' || id WHERE id = :id")
    suspend fun clearIdentityHash(id: Long)

    /**
     * Inserts a subscription-derived profile.
     *
     * `REPLACE` on `(groupId, identityHash)` conflict, deliberately, unlike [ProfileDao]'s plain
     * insert: spec §4.3's documented, bounded limitation is that two servers in one response with
     * byte-identical outbounds but different names collide on that index. `ABORT` (Room's default)
     * would throw and roll back the **whole** sync — directives and every other server in the same
     * response — over one provider sending a duplicate. `REPLACE` instead drops the losing row
     * silently; [art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer] reads the count back
     * afterwards and logs the discrepancy (counts only, §5.6), which is what makes the case
     * diagnosable instead of either silent or fatal.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptionProfile(profile: ProfileEntity): Long

    /** The update half of [insertSubscriptionProfile]'s `REPLACE` reasoning — same index, same trade-off. */
    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSubscriptionProfile(profile: ProfileEntity)

    /**
     * Inserts a subscription-derived profile, or overwrites the existing row's data in place if
     * one already occupies the same (groupId, subscriptionKey) slot — see [ProfileEntity]'s
     * unique index and spec D8: refresh keys on `subscriptionKey`, never `identityHash`, so this
     * mirrors [ProfileDao.upsertProfile]'s structure with the lookup key swapped.
     *
     * Looking up by the **same** column this writes back unchanged is what makes this call, on
     * its own, incapable of ever moving a `subscriptionKey` onto a row another row still holds:
     * the row this matches is by construction the one row already carrying [profile]'s
     * `subscriptionKey`, so the update never assigns a `subscriptionKey` value that isn't already
     * sitting in that exact slot.
     *
     * That leaves the *other* unique index, `(groupId, identityHash)`. A provider that swaps two
     * servers' names between fetches — each keeps its own name's `subscriptionKey`, but the
     * content now sitting behind that key is the *other* server's — needs both matched rows'
     * `identityHash` to trade places in one transaction. Written naively, updating the first row
     * to the second row's (still current) hash collides before the second row has vacated it.
     * [applySync] calls [clearIdentityHash] on every matched row **before** calling this for any
     * of them, so by the time this method's own update runs, no other row in the batch can still
     * be holding the value being written. That is the "re-key within the same transaction before
     * inserting" this constraint calls for — see [applySync]'s KDoc.
     *
     * On the update branch, [ProfileEntity.lastConnectedAt], [ProfileEntity.lastError] and
     * [ProfileEntity.createdAt] are carried over from the existing row, same reasoning as
     * [ProfileDao.upsertProfile]: [profile] is freshly built from what the subscription says
     * *now*, and a plain overwrite would reset "Last used" on every refresh.
     */
    @Transaction
    suspend fun upsertBySubscriptionKey(profile: ProfileEntity) {
        val key = requireNotNull(profile.subscriptionKey) {
            "upsertBySubscriptionKey requires a non-null subscriptionKey"
        }
        val existing = findBySubscriptionKey(profile.groupId, key)
        if (existing != null) {
            updateSubscriptionProfile(
                profile.copy(
                    id = existing.id,
                    lastConnectedAt = existing.lastConnectedAt,
                    lastError = existing.lastError,
                    createdAt = existing.createdAt,
                ),
            )
        } else {
            insertSubscriptionProfile(profile)
        }
    }

    /**
     * Applies one whole sync atomically (spec §6.5).
     *
     * Either the directives, the servers, the group name and the timestamp all
     * land, or none do. A half-applied subscription is the same hazard §A.3.1
     * describes for half-applied routing, one milestone early.
     *
     * Write ordering, and why the unique-index collision this task was flagged
     * against is not reachable:
     *
     * 1. [deleteIds] first. A row absent from the new response is gone before
     *    any insert or update runs, so nothing later in this method can ever
     *    collide with a row that is about to disappear anyway.
     * 2. [clearIdentityHash] on every row [upserts] is about to *update* (found
     *    by [findBySubscriptionKey]), before any of them is given its final
     *    data. Without this pass, a provider swapping two servers' names — each
     *    keeps its own `subscriptionKey`, but the two rows need to trade
     *    `identityHash` values — would collide: writing the first row's new
     *    (target) hash fails because the second row, not yet processed, still
     *    holds it. Clearing every matched row to a per-row-unique placeholder
     *    first means no row in this batch is ever holding a value another row
     *    in the same batch is about to be given.
     * 3. [upsertBySubscriptionKey] for every entry, now safe: each lookup by
     *    `subscriptionKey` finds either a row already neutralised in step 2 (an
     *    update) or no row at all (an insert), and neither case can collide
     *    with anything still in step-2's cleared state.
     *
     * The one remaining collision — two *different* `subscriptionKey`s
     * computing the *same* `identityHash` (byte-identical outbounds under
     * different names) — is spec §4.3's documented, bounded limitation, not an
     * ordering bug; [insertSubscriptionProfile] and [updateSubscriptionProfile]
     * resolve it with `REPLACE` rather than aborting the whole transaction over
     * it, and the syncer logs the resulting count discrepancy.
     */
    @Transaction
    suspend fun applySync(
        subscription: SubscriptionEntity,
        directives: List<SubscriptionDirectiveEntity>,
        groupName: String?,
        upserts: List<ProfileEntity>,
        deleteIds: List<Long>,
    ) {
        putDirectives(directives)
        pruneDirectives(subscription.id, directives.map { it.key })
        groupName?.let { renameGroup(subscription.groupId, it) }
        deleteIds.forEach { deleteProfileById(it) }
        upserts.forEach { profile ->
            val key = requireNotNull(profile.subscriptionKey) {
                "applySync requires every upsert to carry a non-null subscriptionKey"
            }
            findBySubscriptionKey(profile.groupId, key)?.let { clearIdentityHash(it.id) }
        }
        upserts.forEach { upsertBySubscriptionKey(it) }
        updateSubscription(subscription)
    }
}
