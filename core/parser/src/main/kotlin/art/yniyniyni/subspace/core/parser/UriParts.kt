// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import java.net.URLDecoder

internal data class UriParts(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String,
)

private data class UriRemainder(
    val authority: String,
    val fragment: String,
)

private data class AuthorityParts(
    val userInfo: String,
    val host: String,
    val portText: String,
)

private const val SCHEME_SEPARATOR = "://"

@Suppress("ReturnCount")
internal fun parseUri(raw: String): UriParts? {
    val text = raw.trim()
    val schemeEnd = text.indexOf(SCHEME_SEPARATOR)
    if (schemeEnd <= 0) return null

    val scheme = text.substring(0, schemeEnd).lowercase()
    val rest = text.substring(schemeEnd + SCHEME_SEPARATOR.length)
    if (rest.isEmpty()) return null

    val remainder = splitFragment(rest)
    val queryParts = splitQuery(remainder.authority)
    val authority = splitAuthority(queryParts.authority) ?: return null
    return authority.portText.toIntOrNull()?.let { port ->
        UriParts(
            scheme = scheme,
            userInfo = authority.userInfo,
            host = authority.host,
            port = port,
            query = queryParts.query,
            fragment = remainder.fragment,
        )
    }
}

private fun splitFragment(value: String): UriRemainder {
    val fragmentIndex = value.indexOf('#')
    return if (fragmentIndex >= 0) {
        UriRemainder(
            authority = value.substring(0, fragmentIndex),
            fragment = percentDecode(value.substring(fragmentIndex + 1)),
        )
    } else {
        UriRemainder(authority = value, fragment = "")
    }
}

private fun splitQuery(value: String): QueryParts {
    val queryIndex = value.indexOf('?')
    return if (queryIndex >= 0) {
        QueryParts(
            authority = value.substring(0, queryIndex),
            query = parseQuery(value.substring(queryIndex + 1)),
        )
    } else {
        QueryParts(authority = value, query = emptyMap())
    }
}

private data class QueryParts(
    val authority: String,
    val query: Map<String, String>,
)

@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun splitAuthority(value: String): AuthorityParts? {
    val atIndex = value.lastIndexOf('@')
    val userInfo = if (atIndex >= 0) percentDecode(value.substring(0, atIndex)) else ""
    val authority = if (atIndex >= 0) value.substring(atIndex + 1) else value
    if (authority.isEmpty()) return null

    if (authority.startsWith('[')) {
        val closeIndex = authority.indexOf(']')
        if (closeIndex < 0) return null
        val suffix = authority.substring(closeIndex + 1)
        if (!suffix.startsWith(':') || suffix.length == 1) return null
        val host = authority.substring(1, closeIndex)
        if (host.isEmpty()) return null
        return AuthorityParts(userInfo, host, suffix.substring(1))
    }

    val colonIndex = authority.indexOf(':')
    if (colonIndex <= 0 || colonIndex != authority.lastIndexOf(':')) return null
    val host = authority.substring(0, colonIndex)
    if (host.isEmpty()) return null
    return AuthorityParts(userInfo, host, authority.substring(colonIndex + 1))
}

private fun parseQuery(rawQuery: String): Map<String, String> =
    rawQuery
        .split('&')
        .mapNotNull { pair ->
            val equalsIndex = pair.indexOf('=')
            if (equalsIndex <= 0) {
                null
            } else {
                percentDecode(pair.substring(0, equalsIndex)) to
                    percentDecode(pair.substring(equalsIndex + 1))
            }
        }.toMap()

@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun percentDecode(value: String): String =
    try {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }
