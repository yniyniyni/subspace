// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TrojanOutbound

/** Parse a `trojan://password@host:port?params#name` share link. */
@Suppress("ReturnCount")
internal fun parseTrojanLink(
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
    if (uri.userInfo.isEmpty()) {
        return LinkResult.Bad(
            parseFailure(index, ParseFailureReason.MissingCredential, "trojan password is missing"),
        )
    }

    val serverName = uri.query["sni"]?.takeIf { it.isNotBlank() } ?: uri.host
    val fingerprint = uri.query["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
    val allowInsecure = uri.query["allowInsecure"] == "1"
    val security = Security.Tls(serverName, fingerprint, allowInsecure)
    val network = uri.query["type"]?.takeIf { it.isNotBlank() } ?: "tcp"
    val stream = StreamSettings(network = network, security = security)
    val outbound =
        TrojanOutbound(
            address = uri.host,
            port = uri.port,
            password = uri.userInfo,
            stream = stream,
        )
    return LinkResult.Ok(
        Profile(
            id = profileId("trojan", uri.host, uri.port, uri.userInfo),
            name = uri.fragment.ifBlank { uri.host },
            outbound = outbound,
        ),
    )
}
