// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

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
     * Inserts a profile, or leaves the existing row untouched if one already
     * occupies the same (groupId, identityHash) slot — see [ProfileEntity]'s
     * unique index.
     */
    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity): Long

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
