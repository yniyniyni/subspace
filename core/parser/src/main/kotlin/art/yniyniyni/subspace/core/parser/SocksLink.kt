// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.SocksOutbound

/** Parse a `socks://[credentials@]host:port#name` share link. */
@Suppress("ReturnCount")
internal fun parseSocksLink(
    raw: String,
    index: Int,
): LinkResult {
    val uri =
        parseUri(raw)
            ?: return LinkResult.Bad(
                parseFailure(index, ParseFailureReason.MalformedUri, "link is not a usable URI"),
            )

    validatePort(uri.port)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidPort, it))
    }

    val credentials = uri.userInfo.takeIf { it.isNotEmpty() }
    val pair =
        when {
            credentials == null -> null
            credentials.contains(':') -> credentials
            else -> decodeBase64Tolerant(credentials)?.takeIf { it.contains(':') }
        }
    val username = pair?.substringBefore(':')?.takeIf { it.isNotEmpty() }
    val password = pair?.substringAfter(':')?.takeIf { it.isNotEmpty() }
    val outbound =
        SocksOutbound(
            address = uri.host,
            port = uri.port,
            username = username,
            password = password,
        )
    return LinkResult.Ok(
        Profile(
            id = profileId("socks", uri.host, uri.port, username.orEmpty()),
            name = uri.fragment.ifBlank { uri.host },
            outbound = outbound,
        ),
    )
}
