// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound

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
        val suffix = authority.substring(close + 1)
        if (!suffix.startsWith(':') || suffix.length == 1) return null
        val port = parsePortText(suffix.substring(1)) ?: return null
        return SsAuthorityParts(authority.substring(1, close), port)
    }
    if (authority.contains('[') || authority.contains(']')) return null
    if (authority.count { it == ':' } != 1) return null
    val separator = authority.indexOf(':')
    val host = authority.substring(0, separator)
    if (host.isEmpty()) return null
    val port = parsePortText(authority.substring(separator + 1)) ?: return null
    return SsAuthorityParts(host, port)
}

private fun parsePortText(text: String): Int? {
    val value = text.toLongOrNull() ?: return null
    return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else Int.MIN_VALUE
}

private fun identityMaterial(
    method: String,
    password: String,
): String = "${method.length}:$method|${password.length}:$password"

private fun malformed(
    index: Int,
    detail: String,
): LinkResult = LinkResult.Bad(parseFailure(index, ParseFailureReason.MalformedBase64, detail))
