// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.routing

import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.parseRouteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI
import java.util.Base64

private const val MAX_NAME_LENGTH = 64
private const val BASE64_GROUP_SIZE = 4
private const val BASE64_BYTES_PER_GROUP = 3
private const val ROUTING_PATH = "routing/"
private const val ADD_VERB = "add"
private const val ON_ADD_VERB = "onadd"
private const val OFF_VERB = "off"
private val SCHEMES = listOf("happ://", "subspace://")
private val DNS_KEYS =
    listOf(
        "RemoteDNSType",
        "RemoteDNSDomain",
        "RemoteDNSIP",
        "DomesticDNSType",
        "DomesticDNSDomain",
        "DomesticDNSIP",
        "DnsHosts",
        "FakeDNS",
    )
private val BUCKET_KEYS =
    listOf(
        Triple(RouteOutcome.DIRECT, "DirectSites", "DirectIp"),
        Triple(RouteOutcome.PROXY, "ProxySites", "ProxyIp"),
        Triple(RouteOutcome.BLOCK, "BlockSites", "BlockIp"),
    )
private val json = Json { isLenient = true }
private const val MAX_ENCODED_PROFILE_CHARS =
    ((MAX_PROFILE_BYTES + BASE64_BYTES_PER_GROUP - 1) / BASE64_BYTES_PER_GROUP) * BASE64_GROUP_SIZE

/** One decoded payload result, kept private so public failures stay value-free. */
private sealed interface DecodedPayload {
    data class Content(
        val bytes: ByteArray,
    ) : DecodedPayload

    data object TooLarge : DecodedPayload

    data object Malformed : DecodedPayload
}

/** Reads a scalar profile value without treating objects and arrays as strings. */
private fun JsonObject.stringOf(key: String): String? = (this[key] as? JsonPrimitive)?.content

/** Reads a bucket only when every value is a string, preserving entry strictness. */
@Suppress("ReturnCount") // Each short return distinguishes an absent bucket from a malformed one.
private fun JsonObject.stringListOf(key: String): List<String>? {
    val value = this[key] ?: return emptyList()
    val array = value as? JsonArray ?: return null
    return array.map { element ->
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        primitive.content.trim()
    }
}

/** Accepts the string booleans Happ emits and ordinary JSON booleans. */
@Suppress("ReturnCount") // A primitive's JSON and string representations are deliberately separate branches.
private fun JsonObject.looseBooleanOf(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

/** Uses the established default for unknown domain strategies. */
private fun String?.toDomainStrategy(): DomainStrategy =
    DomainStrategy.entries.firstOrNull { strategy ->
        strategy.wireValue.equals(this?.trim(), ignoreCase = true)
    } ?: DomainStrategy.IP_IF_NON_MATCH

/** Stores the DNS field subset as opaque JSON until M6.5 interprets it. */
private fun JsonObject.dnsBlock(): String? {
    val present: Map<String, JsonElement> =
        DNS_KEYS
            .mapNotNull { key ->
                this[key]?.let { key to it }
            }.toMap()
    return if (present.isEmpty()) null else JsonObject(present).toString()
}

/**
 * Parses a Happ-compatible routing deeplink into a [RoutingProfile].
 *
 * All routing import channels call this pure JVM boundary. It returns a typed
 * outcome for every malformed input and does not log or expose profile values.
 */
public object RoutingProfileImport {
    /** Parses [text] without throwing to the caller. */
    public fun parse(text: String): ImportResult =
        runCatching { parseInternal(text) }.getOrElse { ImportResult.Invalid(ImportProblem.MalformedJson) }

    @Suppress("ReturnCount", "UnreachableCode") // K2 detekt misreads the deliberate typed early returns as unreachable.
    private fun parseInternal(text: String): ImportResult {
        val trimmed = text.trim()
        val scheme =
            SCHEMES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
                ?: return ImportResult.Invalid(ImportProblem.NotARoutingLink)
        val rest = trimmed.drop(scheme.length)
        if (!rest.startsWith(ROUTING_PATH, ignoreCase = true)) {
            return ImportResult.Invalid(ImportProblem.NotARoutingLink)
        }

        val afterPath = rest.drop(ROUTING_PATH.length)
        val verbToken = afterPath.substringBefore('/').lowercase()
        val payload = afterPath.substringAfter('/', missingDelimiterValue = "").trim()
        return when (verbToken) {
            OFF_VERB -> ImportResult.DisableRouting
            ADD_VERB -> decodeAndBuild(payload, RoutingVerb.Add)
            ON_ADD_VERB -> decodeAndBuild(payload, RoutingVerb.OnAdd)
            else -> ImportResult.Invalid(ImportProblem.UnknownVerb)
        }
    }

    @Suppress("ReturnCount", "UnreachableCode") // K2 detekt misreads the deliberate typed early returns as unreachable.
    private fun decodeAndBuild(
        payload: String,
        verb: RoutingVerb,
    ): ImportResult {
        when (val decoded = decodeBase64(payload)) {
            DecodedPayload.Malformed -> return ImportResult.Invalid(ImportProblem.MalformedBase64)
            DecodedPayload.TooLarge -> return ImportResult.Invalid(ImportProblem.TooLarge)
            is DecodedPayload.Content -> {
                val root =
                    runCatching {
                        json.parseToJsonElement(String(decoded.bytes, Charsets.UTF_8)) as? JsonObject
                    }.getOrNull() ?: return ImportResult.Invalid(ImportProblem.MalformedJson)
                return buildProfile(root, verb)
            }
        }
    }

    /** Accepts standard/URL-safe and padded/unpadded base64 while bounding allocation. */
    @Suppress(
        "ReturnCount",
        "UnreachableCode",
    ) // K2 detekt misreads these typed malformed-input returns as unreachable.
    private fun decodeBase64(payload: String): DecodedPayload {
        val cleaned = payload.filterNot(Char::isWhitespace)
        if (cleaned.isEmpty()) return DecodedPayload.Malformed
        if (cleaned.length > MAX_ENCODED_PROFILE_CHARS) return DecodedPayload.TooLarge

        val normalised = cleaned.replace('-', '+').replace('_', '/')
        if (normalised.length % BASE64_GROUP_SIZE == 1) return DecodedPayload.Malformed
        val padded =
            normalised.padEnd(
                normalised.length + (BASE64_GROUP_SIZE - normalised.length % BASE64_GROUP_SIZE) % BASE64_GROUP_SIZE,
                '=',
            )
        val bytes =
            runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
                ?: return DecodedPayload.Malformed
        return if (bytes.size > MAX_PROFILE_BYTES) DecodedPayload.TooLarge else DecodedPayload.Content(bytes)
    }

    @Suppress("ReturnCount", "UnreachableCode") // K2 detekt misreads validation's typed early returns as unreachable.
    private fun buildProfile(
        root: JsonObject,
        verb: RoutingVerb,
    ): ImportResult {
        val name = root.stringOf("Name")?.trim().orEmpty()
        if (name.isEmpty()) return ImportResult.Invalid(ImportProblem.MissingName)
        if (name.length > MAX_NAME_LENGTH) return ImportResult.Invalid(ImportProblem.NameTooLong)

        val routeOrder =
            root.stringOf("RouteOrder")?.let { raw ->
                parseRouteOrder(raw) ?: return ImportResult.Invalid(ImportProblem.InvalidRouteOrder)
            } ?: RoutingRuleSet.DEFAULT_ORDER

        val buckets = parseBuckets(root) ?: return ImportResult.Invalid(ImportProblem.InvalidEntry)

        val geoIpUrl = root.stringOf("Geoipurl")?.takeIf(String::isNotBlank)
        val geoSiteUrl = root.stringOf("Geositeurl")?.takeIf(String::isNotBlank)
        listOfNotNull(geoIpUrl, geoSiteUrl).forEach { url ->
            geoUrlProblem(url)?.let { return ImportResult.Invalid(it) }
        }

        val profile =
            RoutingProfile(
                name = name,
                globalProxy = root.looseBooleanOf("GlobalProxy"),
                routeOrder = routeOrder,
                domainStrategy = root.stringOf("DomainStrategy").toDomainStrategy(),
                buckets = buckets,
                geoIpUrl = geoIpUrl,
                geoSiteUrl = geoSiteUrl,
                lastUpdated = root.stringOf("LastUpdated")?.trim()?.toLongOrNull(),
                dnsJson = root.dnsBlock(),
                useChunkFiles = root.looseBooleanOf("UseChunkFiles"),
            )
        return ImportResult.Imported(verb = verb, profile = profile)
    }

    /** Parses and validates all six input buckets before a profile can be imported. */
    @Suppress("ReturnCount") // Each malformed bucket shape must fail the whole profile import.
    private fun parseBuckets(root: JsonObject): Map<RouteOutcome, RuleBucket>? {
        val buckets = mutableMapOf<RouteOutcome, RuleBucket>()
        for ((outcome, siteKey, ipKey) in BUCKET_KEYS) {
            val sites = root.stringListOf(siteKey) ?: return null
            val ips = root.stringListOf(ipKey) ?: return null
            val invalidSites = sites.any { RoutingEntries.problemWith(it, BucketField.SITES) != null }
            val invalidIps = ips.any { RoutingEntries.problemWith(it, BucketField.IPS) != null }
            if (invalidSites || invalidIps) return null
            if (sites.isNotEmpty() || ips.isNotEmpty()) {
                buckets[outcome] = RuleBucket(sites = sites, ips = ips)
            }
        }
        return buckets
    }

    /** Returns the typed reason a geo URL is unsafe or malformed, if any. */
    @Suppress("ReturnCount", "UnreachableCode") // Each URL validation branch maps directly to a typed failure.
    private fun geoUrlProblem(url: String): ImportProblem? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ImportProblem.MalformedGeoUrl
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return ImportProblem.MalformedGeoUrl
        return when {
            uri.scheme.equals("https", ignoreCase = true) -> null
            uri.scheme.equals("http", ignoreCase = true) -> ImportProblem.InsecureGeoUrl
            else -> ImportProblem.MalformedGeoUrl
        }
    }
}
