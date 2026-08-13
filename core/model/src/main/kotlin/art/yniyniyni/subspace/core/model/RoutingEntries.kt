// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/** Which half of a [RuleBucket] an entry belongs to. Decides which prefixes are legal. */
public enum class BucketField { SITES, IPS }

/** Why an entry is not usable. A closed vocabulary; no member carries the entry (§5.6). */
public enum class EntryProblem {
    Blank,
    MissingGeoCode,
    MalformedExtReference,
    MalformedAddress,
    MalformedDomain,

    /** A `"` or `\`, which would break the hand-written config JSON. */
    IllegalCharacter,
}

private const val GEOIP_DAT = "geoip.dat"
private const val GEOSITE_DAT = "geosite.dat"
private val SAFE_GEO_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.dat")

private const val IPV4_GROUPS = 4
private const val IPV4_MAX_OCTET = 255
private const val IPV4_MAX_PREFIX = 32
private const val IPV6_MAX_PREFIX = 128
private const val IPV6_MAX_GROUPS = 8
private const val HEX_RADIX = 16
private const val IPV6_GROUP_MAX_LENGTH = 4
private const val JSON_CONTROL_MAX = 0x1F

/** `ext:` variants that name a domain database, from `ParseDomainRule` (research §3). */
private val SITE_EXT_PREFIXES = listOf("ext:", "ext-domain:", "ext-site:")

/** `ext:` variants that name an IP database, from `ParseIPRules` (research §3). */
private val IP_EXT_PREFIXES = listOf("ext:", "ext-ip:")

/** Domain prefixes `parseCustomDomainRule` understands. None of them touch disk. */
private val DOMAIN_PREFIXES = listOf("domain:", "full:", "keyword:", "dotless:")

private const val REGEXP_PREFIX = "regexp:"

/**
 * Pure classification of a single routing entry.
 *
 * **Every prefix here is read out of xray-core's `common/geodata/rule_parser.go`**
 * at the pinned commit — see `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md`
 * §3, which quotes the source. §10.5: do not add a prefix from memory. An
 * unrecognised prefix here means the editor rejects something Xray would accept;
 * an invented one means the editor accepts something Xray will reject at connect.
 */
@Suppress("TooManyFunctions") // Keeps the entry-classification helpers private to their single public API.
public object RoutingEntries {
    /**
     * The `.dat` file [entry] needs, or null when it resolves without one.
     *
     * Literal CIDRs and literal domains return null, which is why a rule set built
     * only from them activates with no download at all (spec §4.3).
     */
    @Suppress("ReturnCount") // One early return per classification branch keeps the mapping direct.
    public fun geoFileFor(
        entry: String,
        field: BucketField,
    ): String? {
        val bare = entry.trim().removeReversePrefix(field)
        val builtIn = if (field == BucketField.IPS) "geoip:" to GEOIP_DAT else "geosite:" to GEOSITE_DAT
        if (bare.startsWith(builtIn.first)) {
            return if (bare.length > builtIn.first.length) builtIn.second else null
        }
        for (prefix in field.extPrefixes()) {
            if (bare.startsWith(prefix)) {
                val fileName = bare.removePrefix(prefix).substringBefore(':')
                return fileName.takeIf(::isSafeGeoFileName)
            }
        }
        return null
    }

    /** What is wrong with [entry], or null when it is usable. */
    @Suppress("ReturnCount") // One early return per distinct problem; collapsing hides which fired.
    public fun problemWith(
        entry: String,
        field: BucketField,
    ): EntryProblem? {
        val hasIllegalJsonCharacter =
            entry.any { char -> char == '"' || char == '\\' || char.code in 0..JSON_CONTROL_MAX }
        if (hasIllegalJsonCharacter) return EntryProblem.IllegalCharacter
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return EntryProblem.Blank

        val bare = trimmed.removeReversePrefix(field)
        if (bare.isEmpty()) return EntryProblem.Blank

        val builtInPrefix = if (field == BucketField.IPS) "geoip:" else "geosite:"
        if (bare.startsWith(builtInPrefix)) {
            return if (bare.length > builtInPrefix.length) null else EntryProblem.MissingGeoCode
        }

        for (prefix in field.extPrefixes()) {
            if (bare.startsWith(prefix)) return extProblem(bare.removePrefix(prefix))
        }

        return when (field) {
            BucketField.IPS -> if (isAddressOrCidr(bare)) null else EntryProblem.MalformedAddress
            BucketField.SITES -> siteProblem(bare)
        }
    }

    /** `<filename>:<code>`, both non-empty. */
    private fun extProblem(rest: String): EntryProblem? {
        val fileName = rest.substringBefore(':', missingDelimiterValue = "")
        val code = rest.substringAfter(':', missingDelimiterValue = "")
        return if (!isSafeGeoFileName(fileName) || code.isEmpty()) EntryProblem.MalformedExtReference else null
    }

    private fun isSafeGeoFileName(fileName: String): Boolean = SAFE_GEO_FILE_NAME.matches(fileName)

    @Suppress("ReturnCount") // Each branch maps directly to a distinct editor problem.
    private fun siteProblem(bare: String): EntryProblem? {
        if (bare.startsWith(REGEXP_PREFIX)) {
            // Accepted uncompiled: Xray uses Go's regexp, whose syntax is not
            // java.util.regex's. Compiling here would reject valid expressions.
            return if (bare.length > REGEXP_PREFIX.length) null else EntryProblem.Blank
        }
        val body = DOMAIN_PREFIXES.firstOrNull { bare.startsWith(it) }?.let { bare.removePrefix(it) } ?: bare
        if (body.isEmpty()) return EntryProblem.Blank
        val illegal = body.any { it.isWhitespace() || it == '/' || it == ':' }
        return if (illegal) EntryProblem.MalformedDomain else null
    }

    // ReturnCount: guard clauses make address and CIDR validation independently readable.
    // UnreachableCode: detektMain (the type-resolution variant `./gradlew build` runs, unlike
    // the plain `:core:model:detekt` used elsewhere) flags the `?: return false` below as
    // unreachable, which it is not — `prefix` is read on the very next line. Confirmed
    // pre-existing and unrelated to any change in this task: identical on this file's content
    // at commit 3348415, the last commit before Task 11 touched this module. Left unexplained
    // beyond that pending upstream detekt/Kotlin 2.4 triage, since no sourced claim about the
    // root cause is available (§10.5).
    @Suppress("ReturnCount", "UnreachableCode")
    private fun isAddressOrCidr(value: String): Boolean {
        val address = value.substringBefore('/')
        val prefixPart = value.substringAfter('/', missingDelimiterValue = "")
        val isV6 = address.contains(':')
        if (!(if (isV6) isIpv6(address) else isIpv4(address))) return false
        if (!value.contains('/')) return true
        if (prefixPart.isEmpty()) return false
        val prefix = prefixPart.toIntOrNull() ?: return false
        return prefix in 0..(if (isV6) IPV6_MAX_PREFIX else IPV4_MAX_PREFIX)
    }

    private fun isIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != IPV4_GROUPS) return false
        return parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..IPV4_MAX_OCTET
        }
    }

    /**
     * Accepts RFC 4291 compression and an IPv4-mapped tail while rejecting
     * malformed separators and the wrong number of uncompressed groups.
     *
     * CyclomaticComplexMethod/ReturnCount: each branch validates one IPv6 grammar constraint.
     * UnreachableCode: the same detektMain false positive as isAddressOrCidr above, on the
     * `?: return false` inside the `if (hasCompression) { ... }` block — `left`/`right` are
     * both read on the next line.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "UnreachableCode")
    private fun isIpv6(address: String): Boolean {
        if (address.isEmpty() || address.contains(":::")) return false

        val compressionAt = address.indexOf("::")
        val hasCompression = compressionAt >= 0
        if (hasCompression && address.indexOf("::", compressionAt + 2) >= 0) return false

        val groups =
            if (hasCompression) {
                val left = address.substring(0, compressionAt).splitIpv6Groups() ?: return false
                val right = address.substring(compressionAt + 2).splitIpv6Groups() ?: return false
                left + right
            } else {
                address.splitIpv6Groups() ?: return false
            }
        val ipv4TailAt = groups.indexOfFirst { '.' in it }
        if (ipv4TailAt >= 0 && (ipv4TailAt != groups.lastIndex || !isIpv4(groups.last()))) return false

        val hexGroups = if (ipv4TailAt >= 0) groups.dropLast(1) else groups
        if (!hexGroups.all(::isIpv6HexGroup)) return false

        val groupCount = hexGroups.size + if (ipv4TailAt >= 0) 2 else 0
        return if (hasCompression) groupCount < IPV6_MAX_GROUPS else groupCount == IPV6_MAX_GROUPS
    }

    private fun String.splitIpv6Groups(): List<String>? =
        when {
            isEmpty() -> emptyList()
            startsWith(':') || endsWith(':') -> null
            else -> split(':')
        }

    private fun isIpv6HexGroup(group: String): Boolean =
        group.length <= IPV6_GROUP_MAX_LENGTH && group.all { char -> Character.digit(char, HEX_RADIX) >= 0 }

    /** `!` negation, which `cutReversePrefix` accepts on IP rules only (research §3). */
    private fun String.removeReversePrefix(field: BucketField): String =
        when (field) {
            BucketField.IPS -> if (startsWith('!')) removePrefix("!") else this
            BucketField.SITES -> this
        }

    private fun BucketField.extPrefixes(): List<String> =
        when (this) {
            BucketField.IPS -> IP_EXT_PREFIXES
            BucketField.SITES -> SITE_EXT_PREFIXES
        }
}

/**
 * Every `.dat` file this rule set needs on disk before it can be activated.
 *
 * The activation gate's input (spec §4.3). An empty result means the set can be
 * activated immediately with no download.
 */
public fun RoutingRuleSet.requiredGeoFiles(): Set<String> =
    buckets.values
        .flatMap { bucket ->
            bucket.sites.mapNotNull { RoutingEntries.geoFileFor(it, BucketField.SITES) } +
                bucket.ips.mapNotNull { RoutingEntries.geoFileFor(it, BucketField.IPS) }
        }.toSet()
