// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

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

/**
 * The one definition of what a geo database filename may look like.
 *
 * Three places need it and they must not drift apart: this file (which decides
 * whether an `ext:` reference is usable), `GeoAssetRepository` in `:core:data`
 * (which decides what may be written to the geo directory), and `Redaction`'s
 * [redact] exemption. The last is why drift is not merely untidy — widening the
 * redaction copy alone would fail *unsafe*, letting a hostname through §5.6.
 *
 * A `String` rather than a `Regex` so `Redaction` can embed it in a larger
 * pattern; [SAFE_GEO_FILE_NAME] is the compiled form for whole-name matching.
 */
internal const val GEO_FILE_NAME_REGEX = "[A-Za-z0-9][A-Za-z0-9._-]*\\.dat"

internal val SAFE_GEO_FILE_NAME = Regex(GEO_FILE_NAME_REGEX)

/**
 * Whether [name] is a legal geo database filename.
 *
 * Public because `:core:data` enforces the same grammar before staging or
 * installing a file, and a second copy of the expression there is the drift
 * [GEO_FILE_NAME_REGEX] exists to prevent.
 */
public fun isGeoFileName(name: String): Boolean = SAFE_GEO_FILE_NAME.matches(name)

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
     * The `geosite:`/`geoip:` prefix Xray's built-in database uses for [field] — the one
     * [geoFileFor] and [problemWith] both special-case below, and the authority
     * `:feature:routing`'s rule set editor reaches for when it inserts a picked category ahead
     * of a code (`"geosite:" + code`) rather than re-declaring this string a third time. A
     * second copy outside this file is the same drift [GEO_FILE_NAME_REGEX]'s own KDoc warns
     * about for the filename grammar, applied to the prefix instead.
     */
    public fun builtInPrefix(field: BucketField): String = if (field == BucketField.IPS) "geoip:" else "geosite:"

    /**
     * The built-in geo database filename Xray reads for [field] — `geoip.dat`/`geosite.dat`.
     * The authority for the same "which `.dat`" question `GeoAssetRepository`'s install
     * sequence answers for the `.json` sidecar beside it (swap the suffix, do not re-derive
     * the base name).
     */
    public fun builtInGeoFileName(field: BucketField): String = if (field == BucketField.IPS) GEOIP_DAT else GEOSITE_DAT

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
        val builtInPrefix = builtInPrefix(field)
        if (bare.startsWith(builtInPrefix)) {
            return if (bare.length > builtInPrefix.length) builtInGeoFileName(field) else null
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

        val builtInPrefix = builtInPrefix(field)
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
    //
    // UnreachableCode: detektMain — the type-resolution variant `./gradlew build` runs, which
    // the plain `:core:model:detekt` task does not — reports the last two lines of this
    // function as unreachable. They are not, and the proof is a test rather than this comment:
    //
    //   - `prefixPart.toIntOrNull() ?: return false` is reached, and returns, for a
    //     non-numeric prefix — `rejects a non-numeric cidr prefix` ("10.0.0.0/abc").
    //   - the range check on the final line is reached by `rejects a malformed address in an
    //     ip bucket` ("10.0.0.0/33", "fc00::/129") and by every accepted CIDR in
    //     `accepts well-formed entries` ("10.0.0.0/8", "fc00::/7").
    //
    // Delete the suppression and those tests still pass, which is what makes it a false
    // positive and not dead code. Root cause not sourced — §10.5 forbids guessing at one —
    // so this stays until upstream detekt/Kotlin triage explains it.
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
     *
     * UnreachableCode: the same detektMain false positive as `isAddressOrCidr` above, here on
     * the whole `if (hasCompression) { … }` branch. `accepts well-formed entries` reaches it
     * with `fc00::/7`, `::1` and `2001:db8::1` — every compressed literal takes that branch and
     * is accepted through it — and `rejects malformed ipv6 syntax` reaches its `?: return
     * false` exits with `:::1`. Both pass with the suppression removed.
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
