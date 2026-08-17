// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * What a routing rule does with a match.
 *
 * The three tags `:core:xray` emits as `outboundTag` — the `direct` (freedom)
 * and `block` (blackhole) outbounds have existed in every generated config
 * since M1; this milestone is the first to reference them.
 */
public enum class RouteOutcome {
    BLOCK,
    PROXY,
    DIRECT,
}

/**
 * Xray's `routing.domainStrategy`.
 *
 * [wireValue] is the literal the config carries. The three members are the
 * complete set Xray-core accepts; a fourth would be a §10.5 invention.
 */
public enum class DomainStrategy(
    public val wireValue: String,
) {
    AS_IS("AsIs"),
    IP_IF_NON_MATCH("IPIfNonMatch"),
    IP_ON_DEMAND("IPOnDemand"),
}

/**
 * One outcome's entries, split the way Xray splits them.
 *
 * [sites] becomes a rule's `domain` array and [ips] its `ip` array. They are
 * separate fields rather than one list because a single Xray rule matching both
 * would require *both* to match, not either.
 */
public data class RuleBucket(
    val sites: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
) {
    /** True when this bucket would emit no rule at all. */
    public val isEmpty: Boolean get() = sites.isEmpty() && ips.isEmpty()

    /** §5.6: entries are user browsing data. See [RoutingRuleSet.toString]. */
    override fun toString(): String = "RuleBucket(sites=<redacted, ${sites.size}>, ips=<redacted, ${ips.size}>)"
}

/**
 * A named set of routing rules, at most one of which is active at a time.
 *
 * ## Why [order] is a permutation rather than an enum
 *
 * ARCHITECTURE.md §A.3.1 records `RouteOrder` as **unverified** against current
 * Happ documentation — it is observed in community profiles (`"block-proxy-direct"`)
 * but absent from the published schema. A validated permutation absorbs whatever
 * M6 finds there without a model change.
 *
 * This is deliberately the opposite choice from `ServersState`'s sort enum, which
 * M4.5 pinned by test so that adding a member is a deliberate act. There the
 * vocabulary is closed by design; here upstream has not settled it.
 *
 * ## Ordering semantics
 *
 * Xray evaluates rules top to bottom and the first match wins, so [order] decides
 * precedence when the same domain appears in two buckets.
 */
public data class RoutingRuleSet(
    val id: Long = 0,
    val name: String,
    val buckets: Map<RouteOutcome, RuleBucket> = emptyMap(),
    val order: List<RouteOutcome> = DEFAULT_ORDER,
    val domainStrategy: DomainStrategy = DomainStrategy.IP_IF_NON_MATCH,
) {
    init {
        require(order.size == RouteOutcome.entries.size && order.toSet() == RouteOutcome.entries.toSet()) {
            // Names an enum vocabulary, never an entry — §5.6.
            "order must be a permutation of every RouteOutcome, was ${order.size} of ${RouteOutcome.entries.size}"
        }
    }

    /** The entries for [outcome], or an empty bucket when none were stored. */
    public fun bucket(outcome: RouteOutcome): RuleBucket = buckets[outcome] ?: RuleBucket()

    /** Total entries across every bucket. Used for display and for redacted output. */
    public val entryCount: Int
        get() = buckets.values.sumOf { it.sites.size + it.ips.size }

    /**
     * §5.6: every entry is a domain or address the user visits, and the generated
     * data-class `toString()` would print all of them into any log line that
     * interpolates a rule set. A structural guard against a future
     * `Log.d("$ruleSet")`, not a fix for a leak that exists today — the same
     * reasoning as `SubscriptionEntity` and `FetchOutcome.Success`.
     */
    override fun toString(): String =
        "RoutingRuleSet(id=$id, name=$name, order=$order, domainStrategy=$domainStrategy, " +
            "entries=<redacted, $entryCount entries>)"

    public companion object {
        /**
         * Block first, then proxy, then direct — the one value observed in
         * community routing profiles (§A.3.1).
         */
        public val DEFAULT_ORDER: List<RouteOutcome> =
            listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT)
    }
}
