// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

/** Routes one share link to its protocol parser. */
internal fun parseShareLink(
    raw: String,
    index: Int,
): LinkResult {
    val text = raw.trim()
    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) {
        return LinkResult.Bad(
            parseFailure(index, ParseFailureReason.UnknownScheme, "entry is not a share link"),
        )
    }

    return when (text.substring(0, schemeEnd).lowercase()) {
        "vless" -> parseVlessLink(text, index)
        "vmess" -> parseVmessLink(text, index)
        "trojan" -> parseTrojanLink(text, index)
        "ss" -> parseShadowsocksLink(text, index)
        "socks", "socks5" -> parseSocksLink(text, index)
        else ->
            LinkResult.Bad(
                parseFailure(index, ParseFailureReason.UnknownScheme, "unsupported protocol"),
            )
    }
}
