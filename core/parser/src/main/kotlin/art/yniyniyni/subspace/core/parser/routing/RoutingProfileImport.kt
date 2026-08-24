// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.routing

import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.DnsValidation
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.parseRouteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

private const val MAX_NAME_LENGTH = 64
private const val BASE64_GROUP_SIZE = 4
private const val BASE64_BYTES_PER_GROUP = 3
private const val MAX_BASE64_WHITESPACE_CHARS = 4 * 1024
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
private fun JsonObject.stringOf(key: String): String? {
    val primitive = this[key] as? JsonPrimitive
    return primitive?.takeIf(JsonPrimitive::isString)?.content
}

/** Reads `LastUpdated` from a JSON string or number without widening other textual fields. */
private fun JsonObject.lastUpdatedOf(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive
    return primitive?.content?.trim()?.toLongOrNull()
}

/** Decodes profile bytes strictly, so malformed UTF-8 cannot become replacement-character JSON. */
private fun ByteArray.decodeUtf8Strict(): String? =
    runCatching {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    }.getOrNull()

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

/** One resolver triple, or null when the profile named no transport for it. */
@Suppress("ReturnCount") // Each early return names one distinct rejection reason.
private fun JsonObject.resolverOf(prefix: String): DnsResolver? {
    val rawType = stringOf("${prefix}DNSType")?.takeIf(String::isNotBlank) ?: return null
    val transport = DnsTransport.fromWire(rawType) ?: return INVALID_RESOLVER
    val domain = stringOf("${prefix}DNSDomain")?.takeIf(String::isNotBlank)
    val ip = stringOf("${prefix}DNSIP")?.takeIf(String::isNotBlank)

    val valid =
        when (transport) {
            DnsTransport.DOH -> domain != null && DnsValidation.isHttpsUrl(domain)
            DnsTransport.DOU -> ip != null && DnsValidation.isAddressLiteral(ip)
        }
    if (!valid) return INVALID_RESOLVER
    if (ip != null && !DnsValidation.isAddressLiteral(ip)) return INVALID_RESOLVER
    return DnsResolver(transport = transport, domain = domain, ip = ip)
}

/** Marker for a resolver that was named and could not be understood. Never emitted. */
private val INVALID_RESOLVER = DnsResolver(DnsTransport.DOU, domain = null, ip = null)

/** Reads `DnsHosts`, or null when the value is present and malformed. */
@Suppress("ReturnCount") // Each early return names one distinct malformed shape.
private fun JsonObject.dnsHostsOf(): Map<String, String>? {
    val value = this["DnsHosts"] ?: return emptyMap()
    val obj = value as? JsonObject ?: return null
    val out = mutableMapOf<String, String>()
    for ((key, element) in obj) {
        if (key.isBlank()) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        val mapped = primitive.content.trim()
        if (mapped.isEmpty()) return null
        if (!DnsValidation.isAddressLiteral(mapped) && DnsValidation.hostOf("https://$mapped") == null) return null
        out[key.trim()] = mapped
    }
    return out
}

/**
 * The typed DNS block, or null when the profile carries none of its keys.
 *
 * [ProfileDns.INVALID] when the block is present and unusable — spec §5: that
 * does not fail the import, it falls back to the app-level setting and says so.
 */
@Suppress("ReturnCount") // Each early return names one distinct outcome for the block.
internal fun JsonObject.profileDns(): ProfileDns? {
    if (DNS_KEYS.none { this[it] != null }) return null

    val remote = resolverOf("Remote")
    val domestic = resolverOf("Domestic")
    if (remote === INVALID_RESOLVER || domestic === INVALID_RESOLVER) return ProfileDns.INVALID

    val authored = dnsHostsOf() ?: return ProfileDns.INVALID
    val hosts = authored + bootstrapEntries(remote, domestic, authored)

    return ProfileDns(
        remote = remote,
        domestic = domestic,
        hosts = hosts,
        fakeDns = looseBooleanOf("FakeDNS"),
    )
}

/**
 * The `hosts` entries a DoH resolver's own hostname needs (spec §5.1).
 *
 * Upstream recommends mapping a DNS server's domain to its IP directly, to
 * prevent resolution loops (research §3). Only synthesised when the author did
 * not already name that hostname — a different mapping was a choice, and ours
 * must not win.
 */
@Suppress("UnreachableCode") // K2 detekt misreads the deliberate typed early returns as unreachable.
private fun bootstrapEntries(
    remote: DnsResolver?,
    domestic: DnsResolver?,
    authored: Map<String, String>,
): Map<String, String> =
    listOfNotNull(remote, domestic)
        .filter { it.transport == DnsTransport.DOH }
        .mapNotNull { resolver ->
            val host = resolver.domain?.let(DnsValidation::hostOf) ?: return@mapNotNull null
            val ip = resolver.ip?.takeIf(DnsValidation::isAddressLiteral) ?: return@mapNotNull null
            if (authored.containsKey(host)) null else host to ip
        }.toMap()

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
        var start = 0
        var leadingWhitespaceCount = 0
        while (start < text.length && text[start].isWhitespace()) {
            leadingWhitespaceCount += 1
            if (leadingWhitespaceCount > MAX_BASE64_WHITESPACE_CHARS) {
                return ImportResult.Invalid(ImportProblem.TooLarge)
            }
            start += 1
        }
        if (start == text.length) return ImportResult.Invalid(ImportProblem.NotARoutingLink)
        val scheme =
            SCHEMES.firstOrNull { candidate ->
                text.regionMatches(start, candidate, 0, candidate.length, ignoreCase = true)
            }
                ?: return ImportResult.Invalid(ImportProblem.NotARoutingLink)
        val routingStart = start + scheme.length
        if (!text.regionMatches(routingStart, ROUTING_PATH, 0, ROUTING_PATH.length, ignoreCase = true)) {
            return ImportResult.Invalid(ImportProblem.NotARoutingLink)
        }

        val verbStart = routingStart + ROUTING_PATH.length
        return when {
            text.matchesToken(verbStart, OFF_VERB) -> text.offResult(verbStart + OFF_VERB.length)
            text.matchesPayloadVerb(verbStart, ADD_VERB) ->
                decodeAndBuild(text, verbStart + ADD_VERB.length + 1, text.length, RoutingVerb.Add)
            text.matchesPayloadVerb(verbStart, ON_ADD_VERB) ->
                decodeAndBuild(text, verbStart + ON_ADD_VERB.length + 1, text.length, RoutingVerb.OnAdd)
            else -> ImportResult.Invalid(ImportProblem.UnknownVerb)
        }
    }

    /** Compares a link token in place so attacker-sized substrings are never created. */
    private fun String.matchesToken(
        start: Int,
        token: String,
    ): Boolean = start + token.length <= length && regionMatches(start, token, 0, token.length, ignoreCase = true)

    /** Confirms the verb is immediately followed by its payload separator. */
    private fun String.matchesPayloadVerb(
        start: Int,
        token: String,
    ): Boolean = matchesToken(start, token) && start + token.length < length && this[start + token.length] == '/'

    /** Validates `/off`'s optional surrounding whitespace without unbounded suffix scanning. */
    @Suppress("ReturnCount") // Distinguishes an unknown `/off` suffix from an oversized whitespace suffix.
    private fun String.offResult(afterVerb: Int): ImportResult {
        var whitespaceCount = 0
        for (index in afterVerb until length) {
            if (!this[index].isWhitespace()) return ImportResult.Invalid(ImportProblem.UnknownVerb)
            whitespaceCount += 1
            if (whitespaceCount > MAX_BASE64_WHITESPACE_CHARS) {
                return ImportResult.Invalid(ImportProblem.TooLarge)
            }
        }
        return ImportResult.DisableRouting
    }

    @Suppress("ReturnCount", "UnreachableCode") // K2 detekt misreads the deliberate typed early returns as unreachable.
    private fun decodeAndBuild(
        text: String,
        payloadStart: Int,
        payloadEnd: Int,
        verb: RoutingVerb,
    ): ImportResult {
        when (val decoded = decodeBase64(text, payloadStart, payloadEnd)) {
            DecodedPayload.Malformed -> return ImportResult.Invalid(ImportProblem.MalformedBase64)
            DecodedPayload.TooLarge -> return ImportResult.Invalid(ImportProblem.TooLarge)
            is DecodedPayload.Content -> {
                val root =
                    decoded.bytes.decodeUtf8Strict()?.let { decodedText ->
                        runCatching { json.parseToJsonElement(decodedText) as? JsonObject }.getOrNull()
                    } ?: return ImportResult.Invalid(ImportProblem.MalformedJson)
                return buildProfile(root, verb)
            }
        }
    }

    /** Accepts standard/URL-safe and padded/unpadded base64 while bounding allocation. */
    @Suppress(
        "ReturnCount",
        "UnreachableCode",
    ) // K2 detekt misreads these typed malformed-input returns as unreachable.
    private fun decodeBase64(
        text: String,
        start: Int,
        end: Int,
    ): DecodedPayload {
        val normalised = StringBuilder(minOf(end - start, MAX_ENCODED_PROFILE_CHARS))
        var whitespaceCount = 0
        for (index in start until end) {
            val character = text[index]
            if (character.isWhitespace()) {
                whitespaceCount += 1
                if (whitespaceCount > MAX_BASE64_WHITESPACE_CHARS) return DecodedPayload.TooLarge
            } else {
                if (normalised.length >= MAX_ENCODED_PROFILE_CHARS) return DecodedPayload.TooLarge
                normalised.append(
                    when (character) {
                        '-' -> '+'
                        '_' -> '/'
                        else -> character
                    },
                )
            }
        }
        if (normalised.isEmpty()) return DecodedPayload.Malformed
        if (normalised.length % BASE64_GROUP_SIZE == 1) return DecodedPayload.Malformed
        val padded =
            normalised
                .append("=".repeat((BASE64_GROUP_SIZE - normalised.length % BASE64_GROUP_SIZE) % BASE64_GROUP_SIZE))
                .toString()
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
                lastUpdated = root.lastUpdatedOf("LastUpdated"),
                dns = root.profileDns(),
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
