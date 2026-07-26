// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VmessOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parse a `vmess://<base64 of JSON object>` share link. */
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
internal fun parseVmessLink(
    raw: String,
    index: Int,
): LinkResult {
    val trimmed = raw.trim()
    val normalized =
        if (trimmed.startsWith("vmess://", ignoreCase = true)) {
            "vmess://${trimmed.substring("vmess://".length)}"
        } else {
            trimmed
        }
    val body = normalized.removePrefix("vmess://")
    val decoded =
        decodeBase64Tolerant(body)
            ?: return LinkResult.Bad(
                parseFailure(index, ParseFailureReason.MalformedBase64, "vmess body is not base64"),
            )

    val obj =
        parseJsonObject(decoded)
            ?: return LinkResult.Bad(
                parseFailure(index, ParseFailureReason.MalformedJson, "vmess body is not a JSON object"),
            )

    val address = obj.nonBlankString("add")
    if (address == null) {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.MalformedUri, "vmess address is missing"))
    }

    val port = obj.integerOrStringIntOrNull("port")
    if (port == null) {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidPort, "vmess port is not a number"))
    }
    validatePort(port)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidPort, it))
    }

    val uuid = obj.nonBlankString("id")
    if (uuid == null) {
        return LinkResult.Bad(
            parseFailure(index, ParseFailureReason.MissingCredential, "vmess UUID is missing"),
        )
    }
    validateUuid(uuid)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.MissingCredential, it))
    }

    val security =
        if (obj.nonBlankString("tls") == "tls") {
            val serverName = obj.nonBlankString("sni") ?: obj.nonBlankString("host") ?: address
            val fingerprint = obj.nonBlankString("fp") ?: "chrome"
            Security.Tls(serverName, fingerprint, allowInsecure = false)
        } else {
            Security.None
        }
    val stream =
        StreamSettings(
            network = obj.nonBlankString("net") ?: "tcp",
            security = security,
        )
    val alterId =
        if (!obj.containsKey("aid")) {
            0
        } else {
            val primitive = obj["aid"] as? JsonPrimitive
            val value = primitive?.content.orEmpty()
            when {
                primitive == null -> null
                primitive.isString && value.isBlank() -> 0
                else -> value.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            } ?: return LinkResult.Bad(
                parseFailure(index, ParseFailureReason.MalformedJson, "vmess alterId is not a number"),
            )
        }
    val outbound =
        VmessOutbound(
            address = address,
            port = port,
            uuid = uuid,
            alterId = alterId,
            security = obj.nonBlankString("scy") ?: "auto",
            stream = stream,
        )
    return LinkResult.Ok(
        Profile(
            id = profileId("vmess", address, port, uuid),
            name = obj.nonBlankString("ps") ?: address,
            outbound = outbound,
        ),
    )
}

private fun parseJsonObject(text: String): JsonObject? {
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
    return when (element) {
        is JsonObject -> element
        else -> null
    }
}

/** Reads JSON strings and numeric primitives without accepting nested values. */
private fun JsonObject.stringOrNull(key: String): String? {
    val primitive = this[key] as? JsonPrimitive
    val value = primitive?.content
    return if (primitive?.isString == true) value?.takeIf { it.isNotEmpty() } else null
}

private fun JsonObject.integerOrStringIntOrNull(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toIntOrNull()
}

private fun JsonObject.nonBlankString(key: String): String? {
    val value = stringOrNull(key) ?: return null
    return value.takeIf { it.isNotBlank() }
}
