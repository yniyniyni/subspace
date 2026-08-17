// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Data access for [RoutingRuleSetEntity]. */
@Dao
internal interface RoutingRuleSetDao {
    @Query("SELECT * FROM routing_rule_sets ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<RoutingRuleSetEntity>>

    @Query("SELECT * FROM routing_rule_sets WHERE id = :id")
    suspend fun byId(id: Long): RoutingRuleSetEntity?

    @Query("SELECT * FROM routing_rule_sets WHERE name = :name")
    suspend fun byName(name: String): RoutingRuleSetEntity?

    @Insert
    suspend fun insert(entity: RoutingRuleSetEntity): Long

    @Update
    suspend fun update(entity: RoutingRuleSetEntity)

    /**
     * Inserts a new row or updates an existing one by id or unique name.
     *
     * A bare `@Upsert` cannot implement M6's name-collision semantics: when a
     * fresh entity has `id = 0` and clashes on [RoutingRuleSetEntity.name], its
     * generated conflict fallback updates `WHERE id = 0` and silently changes
     * nothing. Resolve the real row first, then update through its actual id.
     *
     * The read and write share one transaction so concurrent imports with the
     * same name cannot both observe an empty slot and race to the unique index.
     * Existing [RoutingRuleSetEntity.createdAt] is retained on either update
     * path; a replacement describes changed rules, not a newly created set.
     */
    @Transaction
    suspend fun upsertByIdOrName(entity: RoutingRuleSetEntity): Long {
        val existing = if (entity.id == 0L) byName(entity.name) else byId(entity.id)
        return if (existing == null) {
            insert(entity)
        } else {
            update(entity.copy(id = existing.id, createdAt = existing.createdAt))
            existing.id
        }
    }

    @Query("DELETE FROM routing_rule_sets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
