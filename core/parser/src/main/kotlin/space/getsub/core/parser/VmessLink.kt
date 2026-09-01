// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.Profile
import space.getsub.core.model.Security
import space.getsub.core.model.StreamSettings
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.VmessOutbound

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
                parseFailure(
                    index,
                    ParseFailureReason.MalformedBase64,
                    FailureDetail.Malformed(DetailField.Base64Body),
                ),
            )

    val obj =
        parseJsonObject(decoded)
            ?: return LinkResult.Bad(
                parseFailure(
                    index,
                    ParseFailureReason.MalformedJson,
                    FailureDetail.Malformed(DetailField.JsonBody),
                ),
            )

    val address = obj.nonBlankString("add")
    if (address == null) {
        return LinkResult.Bad(
            parseFailure(
                index,
                ParseFailureReason.MalformedUri,
                FailureDetail.Missing(DetailField.Address),
            ),
        )
    }

    val port = obj.integerOrStringIntOrNull("port")
    if (port == null) {
        return LinkResult.Bad(
            parseFailure(
                index,
                ParseFailureReason.InvalidPort,
                FailureDetail.Malformed(DetailField.Port),
            ),
        )
    }
    validatePort(port)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.InvalidPort, it))
    }

    val uuid = obj.nonBlankString("id")
    if (uuid == null) {
        return LinkResult.Bad(
            parseFailure(
                index,
                ParseFailureReason.MissingCredential,
                FailureDetail.Missing(DetailField.Uuid),
            ),
        )
    }
    validateUuid(uuid)?.let {
        return LinkResult.Bad(parseFailure(index, ParseFailureReason.MissingCredential, it))
    }

    val security =
        when (val parsed = vmessSecurity(obj, address)) {
            is VmessSecurity.Ok -> parsed.security
            VmessSecurity.Bad ->
                return LinkResult.Bad(
                    parseFailure(
                        index,
                        ParseFailureReason.MalformedJson,
                        FailureDetail.Unsupported(DetailField.Security),
                    ),
                )
        }
    val network = obj.nonBlankString("net") ?: "tcp"
    val stream =
        StreamSettings(
            network = network,
            security = security,
            transport = vmessTransportOptions(obj, network),
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
                parseFailure(
                    index,
                    ParseFailureReason.MalformedJson,
                    FailureDetail.Malformed(DetailField.AlterId),
                ),
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

private sealed interface VmessSecurity {
    data class Ok(
        val security: Security,
    ) : VmessSecurity

    data object Bad : VmessSecurity
}

private enum class VmessTls {
    Enabled,
    Disabled,
    Invalid,
}

private fun vmessSecurity(
    obj: JsonObject,
    address: String,
): VmessSecurity =
    when (vmessTls(obj["tls"])) {
        VmessTls.Disabled -> VmessSecurity.Ok(Security.None)
        VmessTls.Invalid -> VmessSecurity.Bad
        VmessTls.Enabled -> {
            val serverName = obj.nonBlankString("sni") ?: obj.nonBlankString("host") ?: address
            val fingerprint = obj.nonBlankString("fp") ?: "chrome"
            VmessSecurity.Ok(Security.Tls(serverName, fingerprint, allowInsecure = false))
        }
    }

private fun vmessTls(value: kotlinx.serialization.json.JsonElement?): VmessTls {
    val primitive = value as? JsonPrimitive ?: return if (value == null) VmessTls.Disabled else VmessTls.Invalid
    return if (primitive.isString) {
        when {
            primitive.content == "tls" -> VmessTls.Enabled
            primitive.content.isBlank() -> VmessTls.Disabled
            else -> VmessTls.Invalid
        }
    } else {
        when (primitive.content) {
            "true" -> VmessTls.Enabled
            "false" -> VmessTls.Disabled
            else -> VmessTls.Invalid
        }
    }
}

private fun vmessTransportOptions(
    obj: JsonObject,
    network: String,
): TransportOptions =
    when (network) {
        "ws" -> {
            val path = obj.stringPreservingEmpty("path") ?: "/"
            val host = obj.stringPreservingEmpty("host")
            val headers = if (host == null) emptyMap() else mapOf("Host" to host)
            TransportOptions.WebSocket(path = path, headers = headers)
        }

        "grpc" ->
            obj
                .stringPreservingEmpty("path")
                ?.let(TransportOptions::Grpc)
                ?: TransportOptions.None

        else -> TransportOptions.None
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

private fun JsonObject.stringPreservingEmpty(key: String): String? {
    val primitive = this[key] as? JsonPrimitive
    return if (primitive?.isString == true) primitive.content else null
}

private fun JsonObject.integerOrStringIntOrNull(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.content.toIntOrNull()
}

/**
 * `internal`, not `private`: `XrayJsonTransport.kt` needs exactly this reading of an Xray
 * JSON string field (a wrong type or a blank value are both "unset"), and a second copy under
 * the same name and receiver in this module would be an overload ambiguity rather than a
 * convenience. The rest of that family lives in `XrayJsonValues.kt`; this one stays here
 * because it is defined in terms of [stringOrNull], which is this file's own.
 */
internal fun JsonObject.nonBlankString(key: String): String? {
    val value = stringOrNull(key) ?: return null
    return value.takeIf { it.isNotBlank() }
}
