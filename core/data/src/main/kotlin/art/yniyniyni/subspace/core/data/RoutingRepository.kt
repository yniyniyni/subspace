// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("TooManyFunctions") // Public repository contract plus its defensive row codecs stay together.

package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.RoutingRuleSetDao
import art.yniyniyni.subspace.core.data.db.RoutingRuleSetEntity
import art.yniyniyni.subspace.core.data.serialization.ProfileDnsCodec
import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import art.yniyniyni.subspace.core.model.requiredGeoFiles
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
 *
 * @property hasDns whether the row has a (possibly unusable) DNS block at all —
 *   the cheap presence check callers that never need the typed block still use.
 * @property dns the decoded block itself, populated only by [RoutingRepository]'s
 *   own mapper. Defaults to null so the other constructor call sites in this
 *   codebase (tests, `ImportReviewViewModel`) that already pass [hasDns]
 *   explicitly keep compiling unchanged.
 */
public data class StoredRuleSet(
    public val ruleSet: RoutingRuleSet,
    public val sourceKind: RoutingSourceKind?,
    public val subscriptionId: Long?,
    public val lastUpdated: Long?,
    public val fingerprint: String?,
    public val geoIpUrl: String?,
    public val geoSiteUrl: String?,
    public val hasDns: Boolean,
    public val assetGeneration: Long,
    public val assetState: RuleSetAssetState,
    public val assetFailure: RuleSetAssetFailure?,
    public val dns: ProfileDns? = null,
) {
    /**
     * Whether this row's geo files live in its own generation directory rather
     * than the shared catalogue root.
     *
     * **The one definition.** The importer, `RoutingResolver`, the routing list
     * and duplication all ask this question, and answering it three different
     * ways is what produced the defect this property replaces: the resolver used
     * to infer ownership from URL presence, so a literal-only profile that
     * merely *carried* `Geoipurl`/`Geositeurl` resolved to `generationDir(id, 0)`
     * and threw during tunnel startup.
     *
     * Two conditions, both necessary:
     * - the published rules actually **require** a geo file (URLs a profile
     *   carries but never references buy it nothing), and
     * - a positive generation has actually been **published** (generation `0`
     *   means nothing was ever committed for this row).
     *
     * Deliberately provenance-independent: a duplicated hand-made row that owns
     * copied assets is as much a generation owner as an imported one, and
     * special-casing [sourceKind] here would reintroduce a second answer.
     */
    public val usesOwnGeneration: Boolean
        get() = assetGeneration > 0 && ruleSet.requiredGeoFiles().isNotEmpty()

    /**
     * §5.6: entries, geo URLs, DNS data, and their fingerprint never reach logs.
     *
     * [dns] relies on [ProfileDns]'s own [ProfileDns.toString] redaction rather
     * than repeating it here — one redaction to keep in sync, not two.
     */
    override fun toString(): String =
        "StoredRuleSet(ruleSet=$ruleSet, sourceKind=$sourceKind, " +
            "subscriptionId=$subscriptionId, lastUpdated=$lastUpdated, " +
            "fingerprint=<redacted>, geoUrls=<redacted>, " +
            "hasDns=$hasDns, dns=$dns, assetGeneration=$assetGeneration, " +
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

        /**
         * The fingerprint is identical: no rule-row, download, generation, or asset-file write.
         *
         * The importing verb may still update the separate active-rule-set setting.
         */
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

    /** Every imported routing profile currently owned by [subscriptionId]. */
    public suspend fun storedForSubscription(subscriptionId: Long): List<StoredRuleSet> =
        dao.bySubscriptionId(subscriptionId).map { it.toStored() }

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

    /**
     * Inserts a new manual row or updates only its editor-owned routing fields.
     *
     * On an existing id/name, [RoutingRuleSetDao.upsertByIdOrName] preserves
     * imported provenance and asset lifecycle instead of replacing the full
     * Room row with this model's deliberately narrower snapshot.
     */
    public suspend fun upsert(set: RoutingRuleSet): Long {
        set.requireValidEntries()
        if (set.id == 0L) {
            return RoutingProfileProcessCoordinator.withProfiles(listOf(set.name)) { locks ->
                upsertWithinProfileLocks(set, locks)
            }
        }

        var observedCurrentName = dao.byId(set.id)?.name
        while (true) {
            when (
                val attempt =
                    RoutingProfileProcessCoordinator.withProfiles(
                        listOfNotNull(observedCurrentName, set.name),
                    ) { locks ->
                        val lockedCurrentName = dao.byId(set.id)?.name
                        if (lockedCurrentName != null && !locks.holds(lockedCurrentName)) {
                            RenameAttempt.Retry(lockedCurrentName)
                        } else {
                            RenameAttempt.Saved(upsertWithinProfileLocks(set, locks))
                        }
                    }
            ) {
                is RenameAttempt.Retry -> observedCurrentName = attempt.currentName
                is RenameAttempt.Saved -> return attempt.id
            }
        }
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
        // A row that never published successfully is always retryable.
        //
        // §7.3's silent no-op is about a subscription re-delivering content that
        // already landed — its stated purpose is to stop the review sheet
        // becoming an hourly interruption. It was never meant to cover an
        // attempt that failed, and applying it there made a transient download
        // failure permanent: the row kept the incoming fingerprint, so
        // re-importing the byte-identical link answered Unchanged and did no
        // I/O. The only escape was deleting the row.
        //
        // §7.5 is explicit that Failed "clears on a successful retry", and that
        // the refresh cap "exists to stop a chatty profile hammering a CDN, not
        // to tell the device's owner no". This gate is what makes that true.
        if (existing.assetState != RuleSetAssetState.Ready.name) return UpdateDecision.Changed
        // Recomputed from the stored row, never read from the `fingerprint` column.
        //
        // The column records whatever algorithm was current when the row was
        // written, and M6.5 changed that algorithm: `fingerprint()` went from one
        // `feed(dnsJson)` to a fold over the typed DNS projection, which moves the
        // digest of every stored profile — a DNS-less one included, since
        // `feed(null)` still writes a field separator. Comparing against the column
        // would answer `Changed` for every row on the first sync after an upgrade,
        // which is spec §4.3's "one unexplained review sheet per user", or `Stale`
        // for a row carrying a timestamp, silently refusing a real update.
        //
        // Recomputing both sides with the same algorithm makes the comparison
        // answer the question it is actually asking — "is the delivered content the
        // content already applied?" — and makes any future change to the algorithm
        // safe by construction rather than by remembering to write a migration.
        if (existing.toProfile().fingerprint() == profile.fingerprint()) return UpdateDecision.Unchanged
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
     * the replacement atomically. [RoutingRuleSetDao.upsertProfileByName]
     * resolves the collision and performs that targeted update in one transaction,
     * preserving the row id and creation time without carrying a stale row snapshot.
     */
    public suspend fun upsertProfile(
        profile: RoutingProfile,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): Long =
        RoutingProfileProcessCoordinator.withProfiles(listOf(profile.name)) { locks ->
            upsertImportedProfile(profile, sourceKind, subscriptionId, locks)
        }

    /** Claims imported-profile ownership only when [locks] proves this name is serialized. */
    internal suspend fun upsertImportedProfile(
        profile: RoutingProfile,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
        locks: RoutingProfileProcessCoordinator.ProfileLocks,
    ): Long {
        locks.requireHeld(profile.name)
        profile.toRuleSet().requireValidEntries()
        return dao.upsertProfileByName(profile.toEntity(sourceKind, subscriptionId))
    }

    private suspend fun upsertWithinProfileLocks(
        set: RoutingRuleSet,
        locks: RoutingProfileProcessCoordinator.ProfileLocks,
    ): Long {
        locks.requireHeld(set.name)
        dao.byId(set.id)?.name?.let(locks::requireHeld)
        return dao.upsertByIdOrName(set.toEntity())
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
            dnsJson = ProfileDnsCodec.encode(profile.dns),
            useChunkFiles = profile.useChunkFiles,
            generation = generation,
        )
    }

    /**
     * Publishes copied assets as [generation] for a row whose rules already exist.
     *
     * See [RoutingRuleSetDao.publishCopiedGeneration] for why this is not
     * [commitGeneration]: a duplicate is a hand-made row that owns copied files,
     * and must gain no provenance, fingerprint or timestamp from its original.
     */
    public suspend fun publishCopiedGeneration(
        id: Long,
        generation: Long,
    ) {
        dao.publishCopiedGeneration(id, generation)
    }

    /** Removes the rule set with [id]. A no-op when it does not exist. */
    public suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}

private sealed interface RenameAttempt {
    data class Retry(
        val currentName: String,
    ) : RenameAttempt

    data class Saved(
        val id: Long,
    ) : RenameAttempt
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

/**
 * The stored row as the profile it was published from, for fingerprinting.
 *
 * Every field [RoutingProfile.fingerprint] folds is a column on this table, so the
 * projection is faithful; `lastUpdated` rides along for completeness even though
 * the digest excludes it deliberately (it is the freshness gate, not content).
 */
private fun RoutingRuleSetEntity.toProfile(): RoutingProfile {
    val model = toModel()
    return RoutingProfile(
        name = model.name,
        buckets = model.buckets,
        routeOrder = model.order,
        domainStrategy = model.domainStrategy,
        globalProxy = model.globalProxy,
        geoIpUrl = geoIpUrl,
        geoSiteUrl = geoSiteUrl,
        lastUpdated = lastUpdated,
        dns = ProfileDnsCodec.decode(dnsJson),
        useChunkFiles = useChunkFiles,
    )
}

private fun RoutingRuleSetEntity.toStored(): StoredRuleSet =
    StoredRuleSet(
        ruleSet = toModel(),
        sourceKind = RoutingSourceKind.fromWireValue(sourceKind),
        subscriptionId = subscriptionId,
        lastUpdated = lastUpdated,
        fingerprint = fingerprint,
        geoIpUrl = geoIpUrl,
        geoSiteUrl = geoSiteUrl,
        hasDns = !dnsJson.isNullOrBlank(),
        assetGeneration = assetGeneration,
        assetState = assetState.toAssetState(),
        assetFailure = assetFailure.toAssetFailure(),
        dns = ProfileDnsCodec.decode(dnsJson),
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
        // Spec §7.4 step 1: "Approved import writes the row with assetState =
        // Pending." What makes the row retryable is this state, not the absence
        // of a fingerprint — see decideFor.
        assetState = RuleSetAssetState.Pending.name,
        assetFailure = null,
        geoIpUrl = geoIpUrl,
        geoSiteUrl = geoSiteUrl,
        dnsJson = ProfileDnsCodec.encode(dns),
        useChunkFiles = useChunkFiles,
    )
