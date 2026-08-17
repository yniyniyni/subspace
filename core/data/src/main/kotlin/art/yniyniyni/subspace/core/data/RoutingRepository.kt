// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.RoutingRuleSetDao
import art.yniyniyni.subspace.core.data.db.RoutingRuleSetEntity
import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val ENTRY_SEPARATOR = "\n"
private const val ORDER_SEPARATOR = ","

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
    /** Every stored rule set, name-ordered. Re-emits on any write, in either process (§3). */
    public fun observeAll(): Flow<List<RoutingRuleSet>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** The rule set with [id], or null when it has been deleted. */
    public suspend fun ruleSet(id: Long): RoutingRuleSet? = dao.byId(id)?.toModel()

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
    )

private fun String.toDomainStrategy(): DomainStrategy =
    runCatching { DomainStrategy.valueOf(this) }.getOrNull() ?: DomainStrategy.IP_IF_NON_MATCH

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
    )
