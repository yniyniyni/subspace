// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound

/** Parse a `vless://uuid@host:port?params#name` share link. */
@Suppress("ReturnCount")
internal fun parseVlessLink(
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
    validateUuid(uri.userInfo)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.MissingCredential, it))
    }

    val security =
        when (uri.query["security"]) {
            "reality" -> {
                val pbk = uri.query["pbk"].orEmpty()
                validateRealityPublicKey(pbk)?.let {
                    return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidRealityKey, it))
                }
                buildReality(uri, pbk)
            }

            "tls" -> buildTls(uri)
            else -> Security.None
        }

    val stream = StreamSettings(network = uri.query["type"] ?: "tcp", security = security)
    val outbound =
        VlessOutbound(
            address = uri.host,
            port = uri.port,
            uuid = uri.userInfo,
            flow = uri.query["flow"]?.takeIf { it.isNotBlank() },
            stream = stream,
        )
    val id = profileId("vless", uri.host, uri.port, uri.userInfo)
    val name = uri.fragment.ifBlank { uri.host }
    return LinkResult.Ok(Profile(id = id, name = name, outbound = outbound))
}

private fun buildReality(
    uri: UriParts,
    pbk: String,
): Security.Reality {
    val serverName = uri.query["sni"] ?: uri.host
    val shortId = uri.query["sid"].orEmpty()
    val fingerprint = uri.query["fp"] ?: "chrome"
    val spiderX = uri.query["spx"] ?: "/"
    return Security.Reality(serverName, pbk, shortId, fingerprint, spiderX)
}

private fun buildTls(uri: UriParts): Security.Tls {
    val serverName = uri.query["sni"] ?: uri.host
    val fingerprint = uri.query["fp"] ?: "chrome"
    val allowInsecure = uri.query["allowInsecure"] == "1"
    return Security.Tls(serverName, fingerprint, allowInsecure)
}
