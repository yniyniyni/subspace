// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooManyFunctions")

package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound

private const val IPV6_GROUP_COUNT = 8
private const val IPV4_TAIL_GROUP_COUNT = 2
private const val MAX_IPV4_TAIL_COUNT = 1
private const val MAX_HEXTET_LENGTH = 4
private const val IPV4_OCTET_COUNT = 4
private const val MAX_IPV4_OCTET = 255

/** Parses SIP002 and legacy `ss://` share links without throwing. */
@Suppress("ReturnCount")
internal fun parseShadowsocksLink(
    raw: String,
    index: Int,
): LinkResult = parseShadowsocksLinkSafely(raw, index)

@Suppress("ReturnCount")
private fun parseShadowsocksLinkSafely(
    raw: String,
    index: Int,
): LinkResult {
    val text = raw.trim()
    val separator = "://"
    val schemeEnd = text.indexOf(separator)
    if (schemeEnd <= 0 || !text.substring(0, schemeEnd).equals("ss", ignoreCase = true)) {
        return malformed(index, "ss link has no usable body")
    }

    val rest = text.substring(schemeEnd + separator.length)
    val fragmentIndex = rest.indexOf('#')
    val body = if (fragmentIndex >= 0) rest.substring(0, fragmentIndex) else rest
    val name = if (fragmentIndex >= 0) percentDecode(rest.substring(fragmentIndex + 1)) else ""
    if (body.isEmpty()) return malformed(index, "ss link has no body")

    // Decide the format before decoding: an outside-blob '@' is SIP002.
    val parts = if (body.contains('@')) parseSip002(body) else parseLegacy(body)
    if (parts == null) return malformed(index, "ss body is not decodable")

    validatePort(parts.port)?.let { return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidPort, it)) }
    validateShadowsocksMethod(parts.method)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.UnsupportedMethod, it))
    }
    if (parts.password.isEmpty()) {
        return LinkResult.Bad(
            parseFailure(index, ParseFailureReason.MissingCredential, "ss password is missing"),
        )
    }

    val outbound = ShadowsocksOutbound(parts.host, parts.port, parts.method, parts.password)
    val credential = identityMaterial(parts.method, parts.password)
    val id = profileId("ss", parts.host, parts.port, credential)
    return LinkResult.Ok(Profile(id, name.ifBlank { parts.host }, outbound))
}

private data class SsParts(
    val method: String,
    val password: String,
    val host: String,
    val port: Int,
)

private data class SsAuthorityParts(
    val host: String,
    val port: Int,
)

/** `<base64(method:password)>@host:port` */
@Suppress("ReturnCount")
private fun parseSip002(body: String): SsParts? {
    val at = body.lastIndexOf('@')
    if (at <= 0) return null
    val credentials = decodeBase64Tolerant(body.substring(0, at)) ?: return null
    return parseCredentialsAndAuthority(credentials, body.substring(at + 1))
}

/** `<base64(method:password@host:port)>`; split decoded input on the last `@`. */
@Suppress("ReturnCount")
private fun parseLegacy(body: String): SsParts? {
    val decoded = decodeBase64Tolerant(body) ?: return null
    val at = decoded.lastIndexOf('@')
    if (at <= 0) return null
    return parseCredentialsAndAuthority(decoded.substring(0, at), decoded.substring(at + 1))
}

@Suppress("ReturnCount")
private fun parseCredentialsAndAuthority(
    credentials: String,
    authority: String,
): SsParts? {
    val credentialSeparator = credentials.indexOf(':')
    if (credentialSeparator < 0) return null
    val method = credentials.substring(0, credentialSeparator)
    val password = credentials.substring(credentialSeparator + 1)
    val parsedAuthority = parseAuthority(authority) ?: return null
    return SsParts(method, password, parsedAuthority.host, parsedAuthority.port)
}

/** Mirrors [parseUri]'s bracket rule: IPv6 literals must be `[host]:port`. */
@Suppress("ReturnCount")
private fun parseAuthority(authority: String): SsAuthorityParts? {
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close <= 1) return null
        val host = authority.substring(1, close)
        if (!isValidIpv6Literal(host)) return null
        val suffix = authority.substring(close + 1)
        if (!suffix.startsWith(':') || suffix.length == 1) return null
        val port = parsePortText(suffix.substring(1)) ?: return null
        return SsAuthorityParts(host, port)
    }
    if (authority.contains('[') || authority.contains(']')) return null
    if (authority.count { it == ':' } != 1) return null
    val separator = authority.indexOf(':')
    val host = authority.substring(0, separator)
    if (!isValidHostToken(host)) return null
    val port = parsePortText(authority.substring(separator + 1)) ?: return null
    return SsAuthorityParts(host, port)
}

private fun isValidHostToken(host: String): Boolean =
    host.isNotEmpty() &&
        host.none { character ->
            character == '/' ||
                character == '?' ||
                character == '#' ||
                character == '@' ||
                character == '[' ||
                character == ']' ||
                character.isWhitespace() ||
                character.isISOControl()
        }

private fun parsePortText(text: String): Int? {
    val value = text.toLongOrNull() ?: return null
    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else Int.MIN_VALUE
}

@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun isValidIpv6Literal(value: String): Boolean {
    if (value.isEmpty() || value.contains('%')) return false
    val compression = value.indexOf("::")
    if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) return false

    val groups =
        if (compression >= 0) {
            val left = value.substring(0, compression)
            val right = value.substring(compression + 2)
            val leftGroups = if (left.isEmpty()) emptyList() else left.split(':', limit = Int.MAX_VALUE)
            val rightGroups = if (right.isEmpty()) emptyList() else right.split(':', limit = Int.MAX_VALUE)
            leftGroups + rightGroups
        } else {
            value.split(':', limit = Int.MAX_VALUE)
        }
    if (groups.isEmpty() && compression < 0) return false
    val ipv4GroupCount = groups.count { it.contains('.') }
    if (ipv4GroupCount > MAX_IPV4_TAIL_COUNT ||
        (ipv4GroupCount == MAX_IPV4_TAIL_COUNT && groups.lastOrNull()?.contains('.') != true)
    ) {
        return false
    }

    val groupCount = groups.sumOf { if (it.contains('.')) IPV4_TAIL_GROUP_COUNT else 1 }
    return if (compression >= 0) {
        groupCount < IPV6_GROUP_COUNT && groups.all(::isValidIpv6Group)
    } else {
        groupCount == IPV6_GROUP_COUNT && groups.all(::isValidIpv6Group)
    }
}

private fun isValidIpv6Group(group: String): Boolean {
    if (group.contains('.')) return isValidIpv4Tail(group)
    return group.length in 1..MAX_HEXTET_LENGTH &&
        group.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
}

private fun isValidIpv4Tail(value: String): Boolean {
    val octets = value.split('.', limit = Int.MAX_VALUE)
    val hasExpectedOctetCount = octets.size == IPV4_OCTET_COUNT
    val hasValidOctets =
        octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all { it in '0'..'9' } &&
                octet.toIntOrNull()?.let { it in 0..MAX_IPV4_OCTET } == true
        }
    return hasExpectedOctetCount && hasValidOctets
}

private fun identityMaterial(
    method: String,
    password: String,
): String = "${method.length}:$method|${password.length}:$password"

private fun malformed(
    index: Int,
    detail: String,
): LinkResult = LinkResult.Bad(parseFailure(index, ParseFailureReason.MalformedBase64, detail))
