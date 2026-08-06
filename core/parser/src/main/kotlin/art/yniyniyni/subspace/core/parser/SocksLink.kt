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
                parseFailure(
                    index,
                    ParseFailureReason.MalformedUri,
                    FailureDetail.Malformed(DetailField.Uri),
                ),
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
                                FailureDetail.Malformed(DetailField.Base64Body),
                            ),
                        )
                if (!decoded.contains(':')) {
                    return LinkResult.Bad(
                        parseFailure(
                            index,
                            ParseFailureReason.MissingCredential,
                            FailureDetail.Malformed(DetailField.Credential),
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

/**
 * Identity material combining both credential halves.
 *
 * Reused by Clash's `socks5` entries (`ClashYaml.kt`) so that the same server
 * imported from a `socks://` link and from a Clash file lands on one profile
 * ID, not two — the same convention `shadowsocksIdentityMaterial` establishes
 * for `ss`. Folding in only one of the two fields would let two entries that
 * differ solely in username or solely in password collide onto a single ID.
 */
internal fun credentialMaterial(
    username: String?,
    password: String?,
): String = "${identityComponent(username)}|${identityComponent(password)}"

private fun identityComponent(value: String?): String = value?.let { "${it.length}:$it" } ?: "-1:"
