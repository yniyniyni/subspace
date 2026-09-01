// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import java.net.URI

/** How a resolver is reached. The two values Happ's `*DNSType` field carries. */
public enum class DnsTransport(
    public val wireValue: String,
) {
    DOH("DoH"),
    DOU("DoU"),
    ;

    public companion object {
        /** Null for an unrecognised type — the caller decides whether that rejects the block (spec §5). */
        public fun fromWire(raw: String?): DnsTransport? =
            entries.firstOrNull {
                it.wireValue.equals(raw?.trim(), ignoreCase = true)
            }
    }
}

/**
 * One resolver from a profile's DNS block, or from the app-level setting.
 *
 * [domain] is the `https://` endpoint for [DnsTransport.DOH]; [ip] is the plain
 * address for [DnsTransport.DOU], and for DoH it is the **bootstrap** address the
 * resolver's own hostname needs (spec §5.1, research §3).
 *
 * §5.6: both fields are provider-chosen hostnames. See the [toString] override.
 */
public data class DnsResolver(
    public val transport: DnsTransport,
    public val domain: String? = null,
    public val ip: String? = null,
) {
    /**
     * The Xray `address` value for this resolver.
     *
     * **Never a `+local` scheme.** Research §2.1: at v26.7.11 `https+local://` is
     * constructed with a nil dispatcher, so its queries bypass the routing
     * component entirely and leave the tunnel. No routing rule can pull them back.
     */
    public fun xrayAddress(): String? =
        when (transport) {
            DnsTransport.DOH -> domain
            DnsTransport.DOU -> ip
        }

    /** The address a routing rule matches this resolver's own traffic on. */
    public fun routingMatch(): String? =
        when (transport) {
            DnsTransport.DOH -> domain?.let(DnsValidation::hostOf)
            DnsTransport.DOU -> ip
        }

    override fun toString(): String =
        "DnsResolver(transport=$transport, domain=<redacted, present=${domain != null}>, " +
            "ip=<redacted, present=${ip != null}>)"

    public companion object {
        /**
         * The resolver the app ships with, and the app-level setting's own default.
         *
         * Lives here rather than in `:core:xray`'s `DnsPlanner` (which formerly
         * declared it as `DEFAULT_SETTING`) because `:core:data` needs it too, and
         * `:core:data` may not depend on `:core:xray` (ARCHITECTURE.md §4).
         * `DnsPlanner.DEFAULT_SETTING` now aliases this value rather than
         * duplicating the literal.
         */
        public val DEFAULT: DnsResolver = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1")
    }
}

/**
 * A routing profile's DNS block, typed.
 *
 * Replaces M6's opaque `dnsJson` string, which was stored that way on purpose so
 * this milestone could choose the shape rather than inherit a guess.
 *
 * [fakeDns] is nullable rather than defaulted: null means the profile did not say,
 * which is not the same as `"false"`. The first inherits the app default; the
 * second is an author's instruction. Same three-state distinction M6 needed for
 * `GlobalProxy`.
 */
public data class ProfileDns(
    public val remote: DnsResolver? = null,
    public val domestic: DnsResolver? = null,
    public val hosts: Map<String, String> = emptyMap(),
    public val fakeDns: Boolean? = null,
) {
    /** True when the block names at least one resolver. Not the same as "the block is empty". */
    public val hasResolver: Boolean get() = remote != null || domestic != null

    /** True when this is the rejected-block sentinel. See [INVALID]. */
    public val isInvalid: Boolean get() = this === INVALID

    /** §5.6: resolver endpoints and host mappings never reach a log line. */
    override fun toString(): String =
        "ProfileDns(remote=$remote, domestic=$domestic, " +
            "hosts=<redacted, ${hosts.size} entries>, fakeDns=$fakeDns)"

    public companion object {
        /**
         * The block was present and could not be understood (spec §5).
         *
         * A distinct value rather than null, because "no DNS block" and "a DNS
         * block we refused" are different things to the user: the first says
         * nothing, the second must say so on the review sheet.
         */
        public val INVALID: ProfileDns = ProfileDns()
    }
}

/** Shared validation, so the import parser and the settings screen agree on what is valid. */
public object DnsValidation {
    /**
     * True for an IPv4 or IPv6 literal, false for a hostname.
     *
     * Deliberately not `InetAddress.getByName`, which performs a DNS lookup for a
     * hostname — a blocking network call inside a validator (§5.3), and one that
     * would make validation depend on the very resolver being configured.
     */
    public fun isAddressLiteral(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        return IPV4.matches(trimmed) || isIpv6Literal(trimmed)
    }

    /** True when [value] parses as an absolute `https://` URL with a host. */
    public fun isHttpsUrl(value: String): Boolean = hostOf(value) != null

    /** The host component of an `https://` URL, or null when [value] is not one. */
    public fun hostOf(value: String): String? =
        runCatching {
            val uri = URI(value.trim())
            uri.host?.takeIf { uri.scheme.equals("https", ignoreCase = true) && it.isNotBlank() }
        }.getOrNull()

    private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
    private val IPV6_GROUP = Regex("""^[0-9A-Fa-f]{1,4}$""")

    /** Strict IPv6 literal parser that deliberately does not resolve a hostname. */
    private fun isIpv6Literal(value: String): Boolean {
        if (!value.contains(':')) return false

        val compression = value.indexOf("::")
        val groupCount =
            if (compression < 0) {
                ipv6Groups(value)?.size
            } else {
                ipv6GroupCountWithCompression(value, compression)
            }
        return groupCount?.let { count ->
            if (compression < 0) count == IPV6_GROUP_COUNT else count < IPV6_GROUP_COUNT
        } ?: false
    }

    private fun ipv6GroupCountWithCompression(
        value: String,
        compression: Int,
    ): Int? {
        if (value.indexOf("::", compression + 2) >= 0) return null
        val left = ipv6Groups(value.substring(0, compression))
        val right = ipv6Groups(value.substring(compression + 2))
        return if (left == null || right == null) null else left.size + right.size
    }

    private fun ipv6Groups(part: String): List<String>? {
        if (part.isEmpty()) return emptyList()
        val groups = part.split(':')
        return groups.takeIf { values -> values.all(IPV6_GROUP::matches) }
    }

    private const val IPV6_GROUP_COUNT = 8
}
