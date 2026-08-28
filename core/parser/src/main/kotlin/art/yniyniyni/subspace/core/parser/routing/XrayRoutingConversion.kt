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
    /** Keyed on `port`, `network`, `protocol`, `source`, `user`, `inboundTag` or `attrs`. */
    UnsupportedMatcher,

    /** Routed to a balancer. No balancer concept exists in the app's rule model. */
    BalancerRule,

    /** One rule matching `domain` **and** `ip`. Xray needs both; two buckets give either. */
    DomainAndIpInOneRule,

    /** Names an `outboundTag` the config does not define. */
    UnknownOutbound,

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

private val LENIENT =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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
    val appearance = mutableListOf<RouteOutcome>()

    rules.forEach { rule -> classifyRule(rule, outcomeByTag, ::drop, sites, ips, appearance) }

    if (!isRepresentableOrder(rules, outcomeByTag)) drop(ConversionDrop.OrderNotRepresentable)

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
 * Classifies one rule: records its entries under the resolved outcome, or counts
 * why it could not be carried. Kept separate from [convertXrayRouting] so the
 * per-rule branching stays the readable part of each function.
 */
@Suppress("LongParameterList") // Each parameter is a distinct accumulator; bundling them would obscure the branches.
private fun classifyRule(
    rule: JsonObject,
    outcomeByTag: Map<String, RouteOutcome>,
    drop: (ConversionDrop) -> Unit,
    sites: MutableMap<RouteOutcome, MutableList<String>>,
    ips: MutableMap<RouteOutcome, MutableList<String>>,
    appearance: MutableList<RouteOutcome>,
) {
    val domain = rule["domain"] as? JsonArray
    val ip = rule["ip"] as? JsonArray
    when {
        rule["balancerTag"] != null -> drop(ConversionDrop.BalancerRule)
        rule.keys.any { it in UNSUPPORTED_MATCHERS } -> drop(ConversionDrop.UnsupportedMatcher)
        domain != null && ip != null -> drop(ConversionDrop.DomainAndIpInOneRule)
        domain == null && ip == null -> drop(ConversionDrop.UnsupportedMatcher)
        else -> {
            val tag = (rule["outboundTag"] as? JsonPrimitive)?.content
            val outcome = outcomeByTag[tag]
            if (outcome == null) {
                drop(ConversionDrop.UnknownOutbound)
            } else {
                if (outcome !in appearance) appearance += outcome
                domain?.let { sites.getOrPut(outcome, ::mutableListOf) += it.strings() }
                ip?.let { ips.getOrPut(outcome, ::mutableListOf) += it.strings() }
            }
        }
    }
}

/**
 * Maps each outbound tag to the outcome it represents, **by protocol**.
 *
 * Not by tag name: the target panel tags its server outbounds `proxy-auto`,
 * `proxy-auto-2`, … (research §5b.1), so a name-based mapping would fail on
 * exactly the config this feature exists to serve.
 */
private fun outcomeByTag(root: JsonObject): Map<String, RouteOutcome> =
    (root["outbounds"] as? JsonArray)
        .orEmpty()
        .filterIsInstance<JsonObject>()
        .mapNotNull { outbound ->
            val tag = (outbound["tag"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val outcome =
                when ((outbound["protocol"] as? JsonPrimitive)?.content) {
                    "freedom" -> RouteOutcome.DIRECT
                    "blackhole" -> RouteOutcome.BLOCK
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

/** True when each outcome's rules form one contiguous run, which is what a permutation can express. */
private fun isRepresentableOrder(
    rules: List<JsonObject>,
    outcomeByTag: Map<String, RouteOutcome>,
): Boolean {
    val sequence =
        rules.mapNotNull { rule ->
            if (rule["balancerTag"] != null) return@mapNotNull null
            outcomeByTag[(rule["outboundTag"] as? JsonPrimitive)?.content]
        }
    val collapsed =
        sequence.fold(mutableListOf<RouteOutcome>()) { acc, o ->
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
