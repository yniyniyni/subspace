// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Data access for [ProfileGroupEntity] and [ProfileEntity].
 *
 * This is the minimal surface Task 7's [SubspaceDatabaseTest] needs to
 * compile and pass — insert, upsert, cascade-delete a group, and count rows.
 * Task 8 adds the read/query surface the Servers screen and repositories
 * actually consume.
 */
@Dao
internal interface ProfileDao {
    /** Inserts a group, returning its generated row id. */
    @Insert
    suspend fun insertGroup(group: ProfileGroupEntity): Long

    /** Inserts a profile, returning its generated row id. */
    @Insert
    suspend fun insertProfile(profile: ProfileEntity): Long

    /**
     * Inserts a profile, or overwrites the existing row's data in place if
     * one already occupies the same (groupId, identityHash) slot — see
     * [ProfileEntity]'s unique index.
     *
     * Deliberately not a bare `@Upsert`. Room's generated conflict fallback
     * for `@Upsert` runs `UPDATE ... WHERE id = <the passed entity's own
     * id>`, and a freshly constructed [ProfileEntity] — the realistic shape
     * for a re-imported or subscription-refreshed profile — has `id = 0`.
     * On conflict that fallback update then matches no row and the write
     * silently vanishes: no exception, no log, just data that never landed.
     * Looking the real row up by identity first and writing through its
     * actual id avoids that trap entirely.
     */
    suspend fun upsertProfile(profile: ProfileEntity) {
        val existing = findProfile(profile.groupId, profile.identityHash)
        if (existing != null) {
            updateProfile(profile.copy(id = existing.id))
        } else {
            insertProfile(profile)
        }
    }

    /**
     * Looks up a profile by its (groupId, identityHash) slot — see
     * [ProfileEntity]'s unique index. [upsertProfile] uses this to find the
     * real row id to update; it also lets tests prove an upsert's data
     * actually landed, not just that the row count stayed put.
     */
    @Query("SELECT * FROM profiles WHERE groupId = :groupId AND identityHash = :identityHash")
    suspend fun findProfile(
        groupId: Long,
        identityHash: String,
    ): ProfileEntity?

    /** Overwrites an existing row, matched by its primary key. */
    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    /**
     * Deletes a group by id. `ON DELETE CASCADE` on [ProfileEntity]'s foreign
     * key removes every profile in it as part of the same statement.
     */
    @Query("DELETE FROM profile_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    /** Total profile row count across every group. */
    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun profileCount(): Int
}
