// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.DnsValidation
import art.yniyniyni.subspace.core.model.ProfileDns
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The `routing_rule_sets.dnsJson` column's codec.
 *
 * Storage stays one column (spec §4.2): the type and meaning are unchanged from
 * M6, only the canonicalisation is now ours. No Room migration.
 *
 * Written in **Happ's own key names**, not ours. Rows written by M6 hold those
 * keys, and a codec that could not read them would silently drop the DNS block
 * of every profile already on a user's device.
 */
public object ProfileDnsCodec {
    private const val INVALID_KEY = "SubspaceInvalid"
    private val json = Json { isLenient = true }

    /** Canonical form: fixed key order, hosts sorted. Deterministic for a given value. */
    @Suppress("ReturnCount") // Each early return names one storage-shape short-circuit.
    public fun encode(dns: ProfileDns?): String? {
        if (dns == null) return null
        if (dns.isInvalid) return """{"$INVALID_KEY":"true"}"""
        return buildJsonObject {
            dns.remote?.let { resolver ->
                put("RemoteDNSType", resolver.transport.wireValue)
                resolver.domain?.let { put("RemoteDNSDomain", it) }
                resolver.ip?.let { put("RemoteDNSIP", it) }
            }
            dns.domestic?.let { resolver ->
                put("DomesticDNSType", resolver.transport.wireValue)
                resolver.domain?.let { put("DomesticDNSDomain", it) }
                resolver.ip?.let { put("DomesticDNSIP", it) }
            }
            if (dns.hosts.isNotEmpty()) {
                put(
                    "DnsHosts",
                    buildJsonObject {
                        dns.hosts.toSortedMap().forEach { (host, address) -> put(host, address) }
                    },
                )
            }
            dns.fakeDns?.let { put("FakeDNS", it.toString()) }
        }.toString()
    }

    /**
     * Null for absent or unreadable storage; [ProfileDns.INVALID] for a stored
     * rejection **or** a resolver this codec cannot understand.
     *
     * The previous milestone stored this block raw and unvalidated, so a real row
     * can hold e.g. `"RemoteDNSType": "DoQ"`. Decoding that to a resolver-less
     * `ProfileDns` would apply `DnsHosts`/`FakeDNS` while silently dropping the
     * resolver — a half-applied block. Spec §5 makes an unusable block
     * all-or-nothing, matching what the import parser already does with the same
     * bytes (`RoutingProfileImport.profileDns`).
     */
    @Suppress("ReturnCount") // Each early return names one unreadable-storage short-circuit.
    public fun decode(stored: String?): ProfileDns? {
        if (stored.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(stored).jsonObject }.getOrNull() ?: return null
        if (root[INVALID_KEY] != null) return ProfileDns.INVALID

        val remote = root.resolver("Remote")
        val domestic = root.resolver("Domestic")
        if (remote === INVALID_RESOLVER || domestic === INVALID_RESOLVER) return ProfileDns.INVALID

        val hosts = root.storedHosts() ?: return ProfileDns.INVALID

        return ProfileDns(
            remote = remote,
            domestic = domestic,
            hosts = hosts,
            fakeDns = (root["FakeDNS"] as? JsonPrimitive)?.content?.trim()?.lowercase()?.toBooleanOrNull(),
        )
    }

    /**
     * `DnsHosts` as stored, or null when it is present and malformed.
     *
     * Branch review finding 3: this used to coerce every value with
     * `content.orEmpty()` and drop the empties. An array value — legal upstream
     * per research §1.2, and observed on a real panel — coerced to `""` and
     * vanished, leaving the rest of the block applied. That is a half-applied
     * block, which spec §5 makes all-or-nothing. The rules here are
     * `RoutingProfileImport.dnsHostsOf`'s, one for one.
     */
    @Suppress("ReturnCount") // Each early return names one distinct malformed shape.
    private fun JsonObject.storedHosts(): Map<String, String>? {
        val value = this["DnsHosts"] ?: return emptyMap()
        val obj = value as? JsonObject ?: return null
        val out = mutableMapOf<String, String>()
        for ((key, element) in obj) {
            if (key.isBlank()) return null
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val mapped = primitive.content.trim()
            if (mapped.isEmpty()) return null
            if (!DnsValidation.isAddressLiteral(mapped) && DnsValidation.hostOf("https://$mapped") == null) {
                return null
            }
            out[key.trim()] = mapped
        }
        return out
    }

    /** Marker for a resolver whose stored type or address this codec rejects. Never emitted. */
    private val INVALID_RESOLVER = DnsResolver(DnsTransport.DOU, domain = null, ip = null)

    /**
     * One resolver, null when the profile named no transport for it, or [INVALID_RESOLVER].
     *
     * Ruling R8: a stored DoH domain is validated as an `https://` URL, matching
     * the import parser's `resolverOf` (`RoutingProfileImport.kt`). Without this,
     * a row could hold `RemoteDNSType: "DoH"` with a junk domain that decodes
     * cleanly — [DnsResolver.xrayAddress] would then hand the generator that
     * junk string verbatim, producing a config the core rejects outright rather
     * than a block honestly reported as unusable.
     */
    @Suppress("ReturnCount") // Each early return names one distinct outcome, mirroring resolverOf's parser twin.
    private fun JsonObject.resolver(prefix: String): DnsResolver? {
        val rawType = (this["${prefix}DNSType"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) ?: return null
        val transport = DnsTransport.fromWire(rawType) ?: return INVALID_RESOLVER
        val domain = (this["${prefix}DNSDomain"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        val ip = (this["${prefix}DNSIP"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        if (domain == null && ip == null) return null
        // Branch review finding 2: completeness, not merely well-formedness. The
        // parser's `resolverOf` requires the field the transport actually dials —
        // a DoH URL, a DoU literal — and a partial resolver decoded here would
        // have a null `xrayAddress()`, which `DnsPlanner` reads as "the profile
        // named a resolver" while emitting no server at all.
        val complete =
            when (transport) {
                DnsTransport.DOH -> domain != null && DnsValidation.isHttpsUrl(domain)
                DnsTransport.DOU -> ip != null && DnsValidation.isAddressLiteral(ip)
            }
        if (!complete) return INVALID_RESOLVER
        if (ip != null && !DnsValidation.isAddressLiteral(ip)) return INVALID_RESOLVER
        return DnsResolver(transport = transport, domain = domain, ip = ip)
    }

    private fun String.toBooleanOrNull(): Boolean? =
        when (this) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
}
