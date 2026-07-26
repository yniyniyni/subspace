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
            else -> {
                val decoded =
                    decodeBase64Tolerant(credentials)
                        ?: return LinkResult.Bad(
                            parseFailure(
                                index,
                                ParseFailureReason.MalformedBase64,
                                "socks credentials are not valid base64",
                            ),
                        )
                if (!decoded.contains(':')) {
                    return LinkResult.Bad(
                        parseFailure(
                            index,
                            ParseFailureReason.MissingCredential,
                            "socks credentials must contain a separator",
                        ),
                    )
                }
                decoded
            }
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
            id = profileId("socks", uri.host, uri.port, credentialMaterial(username, password)),
            name = uri.fragment.ifBlank { uri.host },
            outbound = outbound,
        ),
    )
}

private fun credentialMaterial(
    username: String?,
    password: String?,
): String = "${identityComponent(username)}|${identityComponent(password)}"

private fun identityComponent(value: String?): String = value?.let { "${it.length}:$it" } ?: "-1:"
