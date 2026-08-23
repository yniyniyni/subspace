// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import java.security.MessageDigest

private const val ROUTE_ORDER_SEPARATOR = "-"
private const val HEX_MASK = 0xFF
private const val FINGERPRINT_FIELD_SEPARATOR: Byte = 0
private const val HEX_DIGIT_WIDTH = 2
private const val HEX_PADDING_CHARACTER = '0'
private const val HEX_RADIX = 16

/**
 * A routing profile as delivered by a provider or a community link.
 *
 * This is the *parsed* shape, not the stored one: [toRuleSet] projects it onto
 * `RoutingRuleSet`, which is what M5 already stores, generates rules from, and
 * gates on. There is no second table and no second rule model — see spec §4.1.
 *
 * Sourced field-by-field in `docs/agent/research/2026-08-20-happ-routing-profiles.md`
 * §3. Two fields there are deliberately treated differently and the difference is
 * the whole §10.5 lesson of this milestone:
 *
 * - [routeOrder] comes from `RouteOrder`, which is undocumented but **describes
 *   itself completely** — a permutation of a closed three-member set. Honoured.
 * - [useChunkFiles] comes from `UseChunkFiles`, which is undocumented and does
 *   **not** describe its own mechanism. Stored, never read. Implementing a
 *   guessed chunk protocol against an unpublished format is exactly the failure
 *   §10.5 exists to prevent.
 *
 * §5.6: [buckets] holds domains and addresses the user visits, and [dns] can
 * hold resolver hostnames. The generated `toString()` would print all of them —
 * see the override.
 *
 * @property name the profile name supplied by its provider or author.
 * @property globalProxy null when the profile did not say. See [RoutingRuleSet.globalProxy].
 * @property routeOrder the precedence of block, proxy, and direct outcomes.
 * @property domainStrategy the Xray domain matching strategy.
 * @property buckets the domains and IP entries grouped by route outcome.
 * @property geoIpUrl the optional provider URL for the geoip database.
 * @property geoSiteUrl the optional provider URL for the geosite database.
 * @property lastUpdated the profile's own unix seconds, or null when absent or
 *   unparseable. Null means the monotonicity gate cannot apply (spec §7.3).
 * @property dns the profile's DNS block, applied by M6.5. Null when the profile
 *   carried none of the DNS keys; [ProfileDns.INVALID] when it carried some and
 *   they could not be understood (spec §5).
 * @property useChunkFiles the provider's chunk-file hint, stored but not applied.
 */
public data class RoutingProfile(
    public val name: String,
    public val globalProxy: Boolean? = null,
    public val routeOrder: List<RouteOutcome> = RoutingRuleSet.DEFAULT_ORDER,
    public val domainStrategy: DomainStrategy = DomainStrategy.IP_IF_NON_MATCH,
    public val buckets: Map<RouteOutcome, RuleBucket> = emptyMap(),
    public val geoIpUrl: String? = null,
    public val geoSiteUrl: String? = null,
    public val lastUpdated: Long? = null,
    /**
     * The profile's DNS block, applied by M6.5.
     *
     * M6 stored this as one opaque `dnsJson` string on purpose, so this milestone
     * could choose the typed shape rather than inherit a guess. This is that shape.
     */
    public val dns: ProfileDns? = null,
    public val useChunkFiles: Boolean? = null,
) {
    /** The entries for [outcome], or an empty bucket. Mirrors [RoutingRuleSet.bucket]. */
    public fun bucket(outcome: RouteOutcome): RuleBucket = buckets[outcome] ?: RuleBucket()

    /** Total entries across every bucket. */
    public val entryCount: Int
        get() = buckets.values.sumOf { it.sites.size + it.ips.size }

    /** True when this profile carries a DNS block, valid or not. */
    public val hasDns: Boolean get() = dns != null

    /**
     * A stable hash of everything that changes what this profile *does*.
     *
     * Drives spec §7.3's silent-no-op rule: a subscription re-delivers its
     * `happRouting` value on every sync, and re-prompting the user for an
     * identical profile would train them to tap through the one confirmation
     * this milestone's security posture rests on.
     *
     * **[lastUpdated] is excluded deliberately.** It is the freshness gate, not
     * content; a provider that bumps only the timestamp has changed nothing the
     * user needs to approve again.
     *
     * Buckets are folded in a fixed [RouteOutcome] order rather than in map
     * iteration order, because a `Map`'s iteration order is a property of its
     * implementation rather than of its contents — the same reasoning
     * `appendWebSocketSettings` sorts for in `:core:xray`.
     */
    public fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun feed(value: String?) {
            digest.update(value.orEmpty().toByteArray(Charsets.UTF_8))
            digest.update(FINGERPRINT_FIELD_SEPARATOR)
        }
        feed(name)
        feed(globalProxy?.toString())
        feed(routeOrder.joinToString(ROUTE_ORDER_SEPARATOR) { it.name })
        feed(domainStrategy.name)
        RouteOutcome.entries.forEach { outcome ->
            feed(bucket(outcome).sites.joinToString("\n"))
            feed(bucket(outcome).ips.joinToString("\n"))
        }
        feed(geoIpUrl)
        feed(geoSiteUrl)
        feed(dns?.remote?.transport?.name)
        feed(dns?.remote?.domain)
        feed(dns?.remote?.ip)
        feed(dns?.domestic?.transport?.name)
        feed(dns?.domestic?.domain)
        feed(dns?.domestic?.ip)
        dns?.hosts?.toSortedMap()?.forEach { (host, address) ->
            feed(host)
            feed(address)
        }
        feed(dns?.fakeDns?.toString())
        feed(dns?.isInvalid?.toString())
        feed(useChunkFiles?.toString())
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and HEX_MASK).toString(HEX_RADIX).padStart(HEX_DIGIT_WIDTH, HEX_PADDING_CHARACTER)
        }
    }

    /**
     * The stored form. [id] is 0 for an import that has not resolved to a row yet;
     * `RoutingRuleSetDao.upsertByIdOrName` resolves a colliding name to the
     * existing row, which is M6's "a name collision is an update" rule (§7.2).
     */
    public fun toRuleSet(id: Long = 0): RoutingRuleSet =
        RoutingRuleSet(
            id = id,
            name = name,
            buckets = buckets,
            order = routeOrder,
            domainStrategy = domainStrategy,
            globalProxy = globalProxy,
        )

    /** §5.6: entries and resolver hostnames never reach a log line. */
    override fun toString(): String =
        "RoutingProfile(name=$name, globalProxy=$globalProxy, order=$routeOrder, " +
            "domainStrategy=$domainStrategy, entries=<redacted, $entryCount entries>, " +
            "geoUrls=<redacted, ${listOfNotNull(geoIpUrl, geoSiteUrl).size}>, " +
            "lastUpdated=$lastUpdated, dns=$dns, " +
            "useChunkFiles=$useChunkFiles)"
}

/**
 * Parses Happ's `RouteOrder`, or null when [raw] is not a permutation.
 *
 * Undocumented upstream; observed as `"block-proxy-direct"`, `"proxy-direct-block"`
 * and `"block-direct-proxy"` in live community profiles (research §3.2). Safe to
 * honour without further sourcing precisely because the value enumerates itself:
 * it names every member of a closed set this project already models.
 *
 * Null rather than a default, so the caller decides whether an unusable value is
 * a rejection or a fallback. `RoutingRuleSet`'s `init` would throw on a
 * non-permutation, and a profile must never be able to crash the routing screen.
 */
public fun parseRouteOrder(raw: String): List<RouteOutcome>? {
    val tokens = raw.trim().split(ROUTE_ORDER_SEPARATOR)
    if (tokens.any { it.isBlank() } || tokens.size != RouteOutcome.entries.size) return null
    val parsed =
        tokens.mapNotNull { token ->
            RouteOutcome.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
        }
    return parsed.takeIf {
        it.size == tokens.size && it.toSet().size == RouteOutcome.entries.size
    }
}
