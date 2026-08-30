// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser.routing

import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RuleBucket
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A way a config's routing exceeds what [RoutingProfile] can express.
 *
 * Counted and shown, never silently discarded: a user who converts a config and
 * then switches routing on would otherwise lose rules with no indication.
 */
public enum class ConversionDrop {
    /** Keyed on `port`, `network`, `protocol`, `source`, `sourcePort`, `user`, `inboundTag` or `attrs`. */
    UnsupportedMatcher,

    /**
     * Carries no `domain`/`ip` matcher and none of [UnsupportedMatcher]'s keys either — an
     * ordinary Xray catch-all that matches unconditionally. Distinct from [UnsupportedMatcher]:
     * that member names a key `RuleBucket` cannot express, but a bare rule has no key at all,
     * so describing it as "keyed on" anything would misstate why it was dropped (§10.4).
     */
    UnconditionalRule,

    /** Routed to a balancer. No balancer concept exists in the app's rule model. */
    BalancerRule,

    /** One rule matching `domain` **and** `ip`. Xray needs both; two buckets give either. */
    DomainAndIpInOneRule,

    /** Names an `outboundTag` the config does not define. */
    UnknownOutbound,

    /**
     * Names an `outboundTag` the config *does* define, but whose protocol is `dns` or
     * `loopback` — [art.yniyniyni.subspace.core.parser.PassthroughAnalysis]'s
     * `NON_SERVER_PROTOCOLS`, alongside `freedom`/`blackhole`. Unlike those two, neither maps
     * onto a [RouteOutcome]: it is not proxied, not sent direct, and not blocked, so folding it
     * into [RouteOutcome.PROXY] (this file's previous behaviour) silently misconverted the rule
     * into one that proxies domains the config never intended to proxy at all. Distinct from
     * [UnknownOutbound]: the tag is real, the protocol just is not one this model can route.
     */
    NonServerOutbound,

    /** The rule order is not a permutation of the three outcomes. Entries are kept; order is approximated. */
    OrderNotRepresentable,

    /** A `dns.hosts` key mapping to several addresses; [ProfileDns.hosts] holds one. */
    MultiAddressHost,
}

/**
 * A converted profile and everything the conversion could not carry.
 *
 * §5.6: [profile] holds domains the user visits, and [RoutingProfile] already
 * redacts. This wrapper must not undo that.
 */
public data class RoutingConversion(
    public val profile: RoutingProfile,
    public val drops: Map<ConversionDrop, Int>,
) {
    override fun toString(): String = "RoutingConversion(profile=$profile, drops=$drops)"
}

// ignoreUnknownKeys governs typed decodeFromString; the only use below is parseToJsonElement,
// which it never affects, so it is left out rather than left looking load-bearing.
private val LENIENT = Json { isLenient = true }

/** Rule keys `RuleBucket` cannot express. Any of these on a rule forces a drop. */
private val UNSUPPORTED_MATCHERS =
    setOf("port", "network", "protocol", "source", "user", "inboundTag", "attrs", "sourcePort")

/**
 * Converts a raw Xray config's `routing` (and `dns`) blocks into a profile the
 * app's importer can review and apply.
 *
 * The app's override branch (spec §3.2) deletes a passthrough config's own
 * routing wholesale; this is how a user keeps it instead. It feeds
 * `RoutingProfileImporter.preview`, so the review sheet, fingerprint gate and
 * geo-download flow are M6's and unchanged.
 *
 * Returns null when there is nothing to convert. Never throws (§7).
 *
 * `geoIpUrl`/`geoSiteUrl` stay null: a pasted config names *files*, not sources.
 * A converted set carrying `geosite:` entries therefore needs a geo source
 * chosen, which the importer already surfaces.
 */
@Suppress("ReturnCount") // Each early return names one distinct reason there is nothing to convert.
public fun convertXrayRouting(
    rawJson: String,
    name: String,
): RoutingConversion? {
    val root = parseRoot(rawJson) ?: return null

    val routing = root["routing"] as? JsonObject ?: return null
    val rules = (routing["rules"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
    if (rules.isEmpty()) return null

    val outcomeByTag = outcomeByTag(root)
    val drops = mutableMapOf<ConversionDrop, Int>()

    fun drop(kind: ConversionDrop) = drops.merge(kind, 1, Int::plus)

    val sites = mutableMapOf<RouteOutcome, MutableList<String>>()
    val ips = mutableMapOf<RouteOutcome, MutableList<String>>()

    // The one source of truth for "which rules actually landed in a bucket": both
    // `appearance` (routeOrder) and the order-representability check below are derived
    // from this same list, so a rule dropped for any reason cannot inflate either.
    val outcomes = rules.mapNotNull { rule -> classifyRule(rule, outcomeByTag, ::drop, sites, ips) }
    val appearance = outcomes.distinct()

    if (!isRepresentableOrder(outcomes)) drop(ConversionDrop.OrderNotRepresentable)

    val buckets =
        RouteOutcome.entries
            .associateWith { outcome ->
                RuleBucket(sites = sites[outcome].orEmpty(), ips = ips[outcome].orEmpty())
            }.filterValues { !it.isEmpty }

    val profile =
        RoutingProfile(
            name = name,
            routeOrder = appearance + RouteOutcome.entries.filter { it !in appearance },
            domainStrategy = domainStrategyOf(routing),
            buckets = buckets,
            dns = dnsOf(root, ::drop),
        )
    return RoutingConversion(profile = profile, drops = drops.toMap())
}

/** Parses [rawJson], or null when it is not a JSON object. Never throws (§7). */
private fun parseRoot(rawJson: String): JsonObject? =
    try {
        LENIENT.parseToJsonElement(rawJson) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * Classifies one rule: records its entries under the resolved outcome and returns it, or
 * counts why the rule could not be carried and returns null. Kept separate from
 * [convertXrayRouting] so the per-rule branching stays the readable part of each function.
 *
 * The return value is deliberately the only signal callers use for "did this rule land
 * somewhere" — a dropped rule (for any [ConversionDrop] reason) always returns null, so
 * appearance order and order-representability can both be derived from it without drifting
 * apart from what actually got dropped.
 */
private fun classifyRule(
    rule: JsonObject,
    outcomeByTag: Map<String, RouteOutcome?>,
    drop: (ConversionDrop) -> Unit,
    sites: MutableMap<RouteOutcome, MutableList<String>>,
    ips: MutableMap<RouteOutcome, MutableList<String>>,
): RouteOutcome? {
    val domain = rule["domain"] as? JsonArray
    val ip = rule["ip"] as? JsonArray
    return when {
        rule["balancerTag"] != null -> {
            drop(ConversionDrop.BalancerRule)
            null
        }
        rule.keys.any { it in UNSUPPORTED_MATCHERS } -> {
            drop(ConversionDrop.UnsupportedMatcher)
            null
        }
        domain != null && ip != null -> {
            drop(ConversionDrop.DomainAndIpInOneRule)
            null
        }
        domain == null && ip == null -> {
            drop(ConversionDrop.UnconditionalRule)
            null
        }
        else -> {
            val outcome = resolveOutcome(rule, outcomeByTag, drop) ?: return null
            domain?.let { sites.getOrPut(outcome, ::mutableListOf) += it.strings() }
            ip?.let { ips.getOrPut(outcome, ::mutableListOf) += it.strings() }
            outcome
        }
    }
}

/**
 * The `outboundTag`-resolution half of [classifyRule], split out to keep that function's
 * cyclomatic complexity under detekt's threshold — this is the sub-branching for exactly one
 * of [classifyRule]'s `when` arms, not a separately reusable concept.
 */
private fun resolveOutcome(
    rule: JsonObject,
    outcomeByTag: Map<String, RouteOutcome?>,
    drop: (ConversionDrop) -> Unit,
): RouteOutcome? {
    val tag = (rule["outboundTag"] as? JsonPrimitive)?.content
    return when {
        tag == null || tag !in outcomeByTag -> {
            drop(ConversionDrop.UnknownOutbound)
            null
        }
        outcomeByTag.getValue(tag) == null -> {
            drop(ConversionDrop.NonServerOutbound)
            null
        }
        else -> outcomeByTag.getValue(tag) ?: error("checked non-null above")
    }
}

/**
 * Maps each outbound tag the config defines to the outcome it represents, **by protocol**.
 *
 * Not by tag name: the target panel tags its server outbounds `proxy-auto`,
 * `proxy-auto-2`, … (research §5b.1), so a name-based mapping would fail on
 * exactly the config this feature exists to serve.
 *
 * A tag maps to `null` when its protocol is `dns` or `loopback` — present in the config
 * (so [ConversionDrop.UnknownOutbound] would misdescribe it) but not one of the three
 * [RouteOutcome]s (so [ConversionDrop.NonServerOutbound] is used instead). Callers must use
 * `containsKey`/`getValue` rather than plain `get` to tell "tag absent" from "tag present but
 * non-routable" apart — both would otherwise read as the same `null`.
 */
private fun outcomeByTag(root: JsonObject): Map<String, RouteOutcome?> =
    (root["outbounds"] as? JsonArray)
        .orEmpty()
        .filterIsInstance<JsonObject>()
        .mapNotNull { outbound ->
            val tag = (outbound["tag"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val outcome =
                when ((outbound["protocol"] as? JsonPrimitive)?.content) {
                    "freedom" -> RouteOutcome.DIRECT
                    "blackhole" -> RouteOutcome.BLOCK
                    // PassthroughAnalysis.NON_SERVER_PROTOCOLS: neither proxied, sent direct,
                    // nor blocked — no RouteOutcome represents these two.
                    "dns", "loopback" -> null
                    else -> RouteOutcome.PROXY
                }
            tag to outcome
        }.toMap()

private fun domainStrategyOf(routing: JsonObject): DomainStrategy =
    when ((routing["domainStrategy"] as? JsonPrimitive)?.content) {
        "AsIs" -> DomainStrategy.AS_IS
        "IPOnDemand" -> DomainStrategy.IP_ON_DEMAND
        else -> DomainStrategy.IP_IF_NON_MATCH
    }

/**
 * True when each outcome's rules form one contiguous run, which is what a permutation can
 * express. [outcomes] must already exclude any rule that was dropped for any reason — it is
 * the same list [convertXrayRouting] derives `appearance` from, so a dropped rule cannot be
 * reported as breaking an order it never contributed to.
 */
private fun isRepresentableOrder(outcomes: List<RouteOutcome>): Boolean {
    val collapsed =
        outcomes.fold(mutableListOf<RouteOutcome>()) { acc, o ->
            if (acc.lastOrNull() != o) acc += o
            acc
        }
    return collapsed.size == collapsed.toSet().size
}

/** Carries `dns.hosts` across, dropping the multi-address entries the model cannot hold. */
private fun dnsOf(
    root: JsonObject,
    drop: (ConversionDrop) -> Unit,
): ProfileDns? {
    val hostsObject = (root["dns"] as? JsonObject)?.get("hosts") as? JsonObject ?: return null
    val hosts =
        hostsObject
            .mapNotNull { (key, value) ->
                when (value) {
                    is JsonPrimitive -> key to value.content
                    else -> {
                        drop(ConversionDrop.MultiAddressHost)
                        null
                    }
                }
            }.toMap()
    return if (hosts.isEmpty()) null else ProfileDns(hosts = hosts)
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }
