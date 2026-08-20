// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooManyFunctions") // Public repository contract plus its defensive row codecs stay together.

package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.RoutingRuleSetDao
import art.yniyniyni.subspace.core.data.db.RoutingRuleSetEntity
import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTRY_SEPARATOR = "\n"
private const val ORDER_SEPARATOR = ","

/**
 * One persisted rule set together with imported-profile provenance and asset state.
 *
 * [geoIpUrl] and [geoSiteUrl] are shown only in informed-consent UI. They, the
 * rule entries, and the stored DNS block are deliberately redacted from
 * [toString] under §5.6.
 */
public data class StoredRuleSet(
    public val ruleSet: RoutingRuleSet,
    public val sourceKind: RoutingSourceKind?,
    public val subscriptionId: Long?,
    public val lastUpdated: Long?,
    public val fingerprint: String?,
    public val geoIpUrl: String?,
    public val geoSiteUrl: String?,
    public val hasUnappliedDns: Boolean,
    public val assetGeneration: Long,
    public val assetState: RuleSetAssetState,
    public val assetFailure: RuleSetAssetFailure?,
) {
    /** §5.6: entries, geo URLs, DNS data, and their fingerprint never reach logs. */
    override fun toString(): String =
        "StoredRuleSet(ruleSet=$ruleSet, sourceKind=$sourceKind, " +
            "subscriptionId=$subscriptionId, lastUpdated=$lastUpdated, " +
            "fingerprint=<redacted>, geoUrls=<redacted>, " +
            "hasUnappliedDns=$hasUnappliedDns, assetGeneration=$assetGeneration, " +
            "assetState=$assetState, assetFailure=$assetFailure)"
}

/**
 * Stored routing rule sets, mapped to and from [RoutingRuleSet].
 *
 * The constructor is `internal` on purpose (§11): production reaches this through
 * Hilt, and a test in another module that needs a real instance goes through
 * `:core:data`'s `testFixtures` (`InMemoryRoutingStack`) rather than reaching for
 * the DAO itself.
 */
@Singleton
public class RoutingRepository
@Inject
internal constructor(
    private val dao: RoutingRuleSetDao,
) {
    /** The result of comparing an incoming profile with the row of the same name. */
    public enum class UpdateDecision {
        /** No row has this profile's name. */
        New,

        /** Meaningful content changed and passed the timestamp gate. */
        Changed,

        /** The fingerprint is identical; callers must perform no later write or download. */
        Unchanged,

        /** Meaningful content changed but its timestamp is not strictly newer. */
        Stale,
    }

    /** Every stored rule set, name-ordered. Re-emits on any write, in either process (§3). */
    public fun observeAll(): Flow<List<RoutingRuleSet>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** Every stored rule set with profile provenance and asset state, name-ordered. */
    public fun observeAllStored(): Flow<List<StoredRuleSet>> =
        dao.observeAll().map { rows -> rows.map { it.toStored() } }

    /** The rule set with [id], or null when it has been deleted. */
    public suspend fun ruleSet(id: Long): RoutingRuleSet? = dao.byId(id)?.toModel()

    /** The stored rule set with [id], including provenance, or null after deletion. */
    public suspend fun stored(id: Long): StoredRuleSet? = dao.byId(id)?.toStored()

    /**
     * The rule set named [name], or null when no row has it.
     *
     * [RoutingRuleSetEntity.name] is uniquely indexed, so at most one row can
     * match. Exists for the rule set editor's save-time collision check:
     * [upsert]'s `id == 0` branch resolves a fresh entity **by name** and
     * updates that row in place — deliberate for M6 import (see
     * [RoutingRuleSetDao.upsertByIdOrName]'s own KDoc), but silent data loss
     * for an interactive create. The editor calls this first and refuses the
     * save itself rather than relying on [upsert] to reject anything — it
     * never does, by design.
     */
    public suspend fun ruleSetNamed(name: String): RoutingRuleSet? = dao.byName(name)?.toModel()

    /** Inserts or updates, returning the row id. */
    public suspend fun upsert(set: RoutingRuleSet): Long {
        set.requireValidEntries()
        return dao.upsertByIdOrName(set.toEntity())
    }

    /**
     * Decides what an incoming [profile] means without writing anything.
     *
     * The gates run in this order on purpose:
     *
     * 1. **Fingerprint first.** Identical content is [UpdateDecision.Unchanged]
     *    regardless of timestamp. A subscription re-delivers the same profile
     *    hourly; prompting or writing each time would destroy the review sheet's
     *    security value (spec §7.3).
     * 2. **`LastUpdated` second.** Different content is accepted only when its
     *    timestamp is strictly newer. If either side has no timestamp, there is
     *    no comparable stale gate and the update is [UpdateDecision.Changed].
     */
    @Suppress("ReturnCount") // Each early exit names one ordered security/freshness gate.
    public suspend fun decideFor(profile: RoutingProfile): UpdateDecision {
        val existing = dao.byName(profile.name) ?: return UpdateDecision.New
        if (existing.fingerprint == profile.fingerprint()) return UpdateDecision.Unchanged
        val incoming = profile.lastUpdated ?: return UpdateDecision.Changed
        val stored = existing.lastUpdated ?: return UpdateDecision.Changed
        return if (incoming > stored) UpdateDecision.Changed else UpdateDecision.Stale
    }

    /**
     * Resolves [profile] to one row, updating a same-name row rather than duplicating it.
     *
     * A new row receives the profile's rules and provenance. On collision, only
     * source ownership moves now: the existing rules, fingerprint, timestamp,
     * generation, and asset state remain live until [commitGeneration] publishes
     * the replacement atomically. [RoutingRuleSetDao.upsertByIdOrName] preserves
     * the row id and creation time on both paths.
     */
    public suspend fun upsertProfile(
        profile: RoutingProfile,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): Long {
        profile.toRuleSet().requireValidEntries()
        val existing = dao.byName(profile.name)
        val entity =
            existing?.copy(
                sourceKind = sourceKind.wireValue,
                subscriptionId = subscriptionId,
            ) ?: profile.toEntity(sourceKind, subscriptionId)
        return dao.upsertByIdOrName(entity)
    }

    /** Writes [state] and [failure] together so observers never see a torn pair. */
    public suspend fun markAssets(
        id: Long,
        state: RuleSetAssetState,
        failure: RuleSetAssetFailure? = null,
    ) {
        dao.updateAssetState(id, state.name, failure?.name)
    }

    /**
     * Atomically publishes [profile]'s rules and metadata as [generation].
     *
     * The DAO performs the complete swap in one SQL statement, including
     * `Ready` and clearing the prior failure; splitting it would permit observers
     * to see rule columns and the live generation disagree (spec §7.4).
     */
    public suspend fun commitGeneration(
        id: Long,
        profile: RoutingProfile,
        generation: Long,
    ) {
        profile.toRuleSet(id).requireValidEntries()
        dao.commitGeneration(
            id = id,
            directSites = profile.bucket(RouteOutcome.DIRECT).sites.toColumn(),
            directIps = profile.bucket(RouteOutcome.DIRECT).ips.toColumn(),
            proxySites = profile.bucket(RouteOutcome.PROXY).sites.toColumn(),
            proxyIps = profile.bucket(RouteOutcome.PROXY).ips.toColumn(),
            blockSites = profile.bucket(RouteOutcome.BLOCK).sites.toColumn(),
            blockIps = profile.bucket(RouteOutcome.BLOCK).ips.toColumn(),
            routeOrder = profile.routeOrder.joinToString(ORDER_SEPARATOR) { it.name },
            domainStrategy = profile.domainStrategy.name,
            globalProxy = profile.globalProxy,
            lastUpdated = profile.lastUpdated,
            fingerprint = profile.fingerprint(),
            geoIpUrl = profile.geoIpUrl,
            geoSiteUrl = profile.geoSiteUrl,
            dnsJson = profile.dnsJson,
            useChunkFiles = profile.useChunkFiles,
            generation = generation,
        )
    }

    /** Removes the rule set with [id]. A no-op when it does not exist. */
    public suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}

/**
 * `split` on an empty string yields a single empty element, which would become a
 * bucket holding one blank entry — a rule matching nothing, emitted into the
 * config for no reason. Filtering is what makes an empty column read back as an
 * empty list.
 */
private fun String.toEntries(): List<String> =
    split(ENTRY_SEPARATOR).filter { it.isNotBlank() }

private fun List<String>.toColumn(): String = joinToString(ENTRY_SEPARATOR)

/** Rejects entries that newline-delimited storage cannot round-trip exactly. */
private fun RoutingRuleSet.requireValidEntries() {
    RouteOutcome.entries.forEach { outcome ->
        bucket(outcome).sites.requireValidEntries(BucketField.SITES)
        bucket(outcome).ips.requireValidEntries(BucketField.IPS)
    }
}

private fun List<String>.requireValidEntries(field: BucketField) {
    forEach { entry ->
        require(RoutingEntries.problemWith(entry, field) == null) {
            "routing rule set contains an invalid $field entry"
        }
    }
}

private fun RoutingRuleSetEntity.toModel(): RoutingRuleSet =
    RoutingRuleSet(
        id = id,
        name = name,
        buckets = mapOf(
            RouteOutcome.DIRECT to RuleBucket(directSites.toEntries(), directIps.toEntries()),
            RouteOutcome.PROXY to RuleBucket(proxySites.toEntries(), proxyIps.toEntries()),
            RouteOutcome.BLOCK to RuleBucket(blockSites.toEntries(), blockIps.toEntries()),
        ),
        order = routeOrder.toOrder(),
        // A stored value is always a valid name. This guards a hand-edited or
        // future-version row rather than crashing the routing screen on it —
        // the same defensive read SettingsRepository.theme performs.
        domainStrategy = domainStrategy.toDomainStrategy(),
        globalProxy = globalProxy,
    )

private fun RoutingRuleSetEntity.toStored(): StoredRuleSet =
    StoredRuleSet(
        ruleSet = toModel(),
        sourceKind = RoutingSourceKind.fromWireValue(sourceKind),
        subscriptionId = subscriptionId,
        lastUpdated = lastUpdated,
        fingerprint = fingerprint,
        geoIpUrl = geoIpUrl,
        geoSiteUrl = geoSiteUrl,
        hasUnappliedDns = !dnsJson.isNullOrBlank(),
        assetGeneration = assetGeneration,
        assetState = assetState.toAssetState(),
        assetFailure = assetFailure.toAssetFailure(),
    )

private fun String.toDomainStrategy(): DomainStrategy =
    runCatching { DomainStrategy.valueOf(this) }.getOrNull() ?: DomainStrategy.IP_IF_NON_MATCH

private fun String.toAssetState(): RuleSetAssetState =
    RuleSetAssetState.entries.firstOrNull { it.name == this } ?: RuleSetAssetState.None

private fun String?.toAssetFailure(): RuleSetAssetFailure? =
    RuleSetAssetFailure.entries.firstOrNull { it.name == this }

/**
 * A stored order that is not a permutation would make [RoutingRuleSet]'s `init`
 * throw while reading a row, taking down the screen. Falling back to the default
 * keeps a corrupted row visible and editable instead.
 */
private fun String.toOrder(): List<RouteOutcome> {
    val parsed =
        split(ORDER_SEPARATOR).mapNotNull { name ->
            runCatching { RouteOutcome.valueOf(name.trim()) }.getOrNull()
        }
    val isPermutation =
        parsed.size == RouteOutcome.entries.size && parsed.toSet() == RouteOutcome.entries.toSet()
    return if (isPermutation) parsed else RoutingRuleSet.DEFAULT_ORDER
}

private fun RoutingRuleSet.toEntity(): RoutingRuleSetEntity =
    RoutingRuleSetEntity(
        id = id,
        name = name,
        directSites = bucket(RouteOutcome.DIRECT).sites.toColumn(),
        directIps = bucket(RouteOutcome.DIRECT).ips.toColumn(),
        proxySites = bucket(RouteOutcome.PROXY).sites.toColumn(),
        proxyIps = bucket(RouteOutcome.PROXY).ips.toColumn(),
        blockSites = bucket(RouteOutcome.BLOCK).sites.toColumn(),
        blockIps = bucket(RouteOutcome.BLOCK).ips.toColumn(),
        routeOrder = order.joinToString(ORDER_SEPARATOR) { it.name },
        domainStrategy = domainStrategy.name,
        createdAt = System.currentTimeMillis(),
        globalProxy = globalProxy,
    )

private fun RoutingProfile.toEntity(
    sourceKind: RoutingSourceKind,
    subscriptionId: Long?,
): RoutingRuleSetEntity =
    toRuleSet().toEntity().copy(
        sourceKind = sourceKind.wireValue,
        subscriptionId = subscriptionId,
        lastUpdated = lastUpdated,
        fingerprint = fingerprint(),
        geoIpUrl = geoIpUrl,
        geoSiteUrl = geoSiteUrl,
        dnsJson = dnsJson,
        useChunkFiles = useChunkFiles,
    )
