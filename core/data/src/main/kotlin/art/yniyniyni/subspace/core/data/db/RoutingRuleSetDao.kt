// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Data access for [RoutingRuleSetEntity]. */
@Dao
@Suppress("TooManyFunctions") // A DAO's surface is the width of its table's atomic operations.
internal interface RoutingRuleSetDao {
    @Query("SELECT * FROM routing_rule_sets ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<RoutingRuleSetEntity>>

    @Query("SELECT * FROM routing_rule_sets WHERE id = :id")
    suspend fun byId(id: Long): RoutingRuleSetEntity?

    @Query("SELECT * FROM routing_rule_sets WHERE name = :name")
    suspend fun byName(name: String): RoutingRuleSetEntity?

    @Query("SELECT * FROM routing_rule_sets WHERE subscriptionId = :subscriptionId ORDER BY name")
    suspend fun bySubscriptionId(subscriptionId: Long): List<RoutingRuleSetEntity>

    @Insert
    suspend fun insert(entity: RoutingRuleSetEntity): Long

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
     * Existing rows move only through [updateEditableColumns]: provenance,
     * subscription ownership, timestamps, profile metadata, and asset lifecycle
     * remain database-owned rather than being reset by a stale editor snapshot.
     */
    @Transaction
    suspend fun upsertByIdOrName(entity: RoutingRuleSetEntity): Long {
        val existing = if (entity.id == 0L) byName(entity.name) else byId(entity.id)
        return if (existing == null) {
            insert(entity)
        } else {
            updateEditableColumns(
                id = existing.id,
                name = entity.name,
                directSites = entity.directSites,
                directIps = entity.directIps,
                proxySites = entity.proxySites,
                proxyIps = entity.proxyIps,
                blockSites = entity.blockSites,
                blockIps = entity.blockIps,
                routeOrder = entity.routeOrder,
                domainStrategy = entity.domainStrategy,
            )
            existing.id
        }
    }

    /** Updates exactly the explicit rule and naming columns owned by a manual/editor save. */
    @Query(
        """
        UPDATE routing_rule_sets SET
            name = :name,
            directSites = :directSites, directIps = :directIps,
            proxySites = :proxySites, proxyIps = :proxyIps,
            blockSites = :blockSites, blockIps = :blockIps,
            routeOrder = :routeOrder, domainStrategy = :domainStrategy
        WHERE id = :id
        """,
    )
    @Suppress("LongParameterList") // One parameter per editor-owned column in the atomic update.
    suspend fun updateEditableColumns(
        id: Long,
        name: String,
        directSites: String,
        directIps: String,
        proxySites: String,
        proxyIps: String,
        blockSites: String,
        blockIps: String,
        routeOrder: String,
        domainStrategy: String,
    )

    /**
     * Inserts a new imported profile or updates only safe provenance on collision.
     *
     * The lookup and write share one transaction, preserving the unique-name
     * guarantee under concurrent imports. An existing row is deliberately never
     * passed through [update]: a generation commit can land at any time, and a
     * stale full entity would restore old rules, metadata, generation, state, and
     * failure. Rules and profile metadata move only through [commitGeneration].
     */
    @Transaction
    suspend fun upsertProfileByName(entity: RoutingRuleSetEntity): Long {
        val existing = byName(entity.name)
        return if (existing == null) {
            insert(entity)
        } else {
            updateProfileProvenance(existing.id, entity.sourceKind, entity.subscriptionId)
            existing.id
        }
    }

    /** Updates only ownership fields that are safe before generation publication. */
    @Query(
        """
        UPDATE routing_rule_sets
        SET sourceKind = :sourceKind, subscriptionId = :subscriptionId
        WHERE id = :id
        """,
    )
    suspend fun updateProfileProvenance(
        id: Long,
        sourceKind: String?,
        subscriptionId: Long?,
    )

    /**
     * Publishes a generation whose rules are already stored — the duplicate path.
     *
     * Distinct from [commitGeneration]: a copy's rules were written by the
     * ordinary upsert and must not be overwritten from a profile, and it
     * deliberately gains no fingerprint, timestamp or geo URLs. It is a
     * hand-made row that happens to own copied assets.
     */
    @Query(
        """
        UPDATE routing_rule_sets
        SET assetGeneration = :generation, assetState = 'Ready', assetFailure = NULL
        WHERE id = :id
        """,
    )
    suspend fun publishCopiedGeneration(
        id: Long,
        generation: Long,
    )

    /** Updates the persistent asset state and failure as one inseparable pair. */
    @Query("UPDATE routing_rule_sets SET assetState = :state, assetFailure = :failure WHERE id = :id")
    suspend fun updateAssetState(
        id: Long,
        state: String,
        failure: String?,
    )

    /**
     * Atomically publishes a fully materialised profile generation (spec §7.4).
     *
     * Rules and [generation] move in one statement, together with the successful
     * state/failure pair, so no observer can see rules referring to a generation
     * that is not live yet. This guarantee is why these columns are deliberately
     * not split across repository calls.
     */
    @Query(
        """
        UPDATE routing_rule_sets SET
            directSites = :directSites, directIps = :directIps,
            proxySites = :proxySites, proxyIps = :proxyIps,
            blockSites = :blockSites, blockIps = :blockIps,
            routeOrder = :routeOrder, domainStrategy = :domainStrategy,
            globalProxy = :globalProxy, lastUpdated = :lastUpdated,
            fingerprint = :fingerprint, geoIpUrl = :geoIpUrl, geoSiteUrl = :geoSiteUrl,
            dnsJson = :dnsJson, useChunkFiles = :useChunkFiles,
            assetGeneration = :generation, assetState = 'Ready', assetFailure = NULL
        WHERE id = :id
        """,
    )
    @Suppress("LongParameterList") // One parameter per column that the atomic publication moves.
    suspend fun commitGeneration(
        id: Long,
        directSites: String,
        directIps: String,
        proxySites: String,
        proxyIps: String,
        blockSites: String,
        blockIps: String,
        routeOrder: String,
        domainStrategy: String,
        globalProxy: Boolean?,
        lastUpdated: Long?,
        fingerprint: String?,
        geoIpUrl: String?,
        geoSiteUrl: String?,
        dnsJson: String?,
        useChunkFiles: Boolean?,
        generation: Long,
    )

    @Query("DELETE FROM routing_rule_sets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
