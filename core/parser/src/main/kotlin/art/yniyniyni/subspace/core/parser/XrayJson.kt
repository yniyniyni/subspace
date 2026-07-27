// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.VlessOutbound
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Extracts supported server profiles from a complete raw Xray JSON object. */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun parseXrayJson(text: String): ParseOutcome {
    val root =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
            ?: return ParseOutcome(
                emptyList(),
                listOf(parseFailure(0, ParseFailureReason.MalformedJson, "config is not a JSON object")),
            )

    val outbounds = root["outbounds"] as? JsonArray ?: return ParseOutcome.EMPTY
    val profiles = mutableListOf<Profile>()
    val failures = mutableListOf<ParseFailure>()

    outbounds.forEachIndexed { index, element ->
        val outbound = element as? JsonObject ?: return@forEachIndexed
        when (val protocol = outbound["protocol"]) {
            null, JsonNull -> Unit
            is JsonPrimitive -> {
                when (protocol.stringContent()) {
                    "freedom", "blackhole" -> Unit

                    null ->
                        failures +=
                            parseFailure(index, ParseFailureReason.UnknownScheme, "outbound protocol is not supported")

                    "vless" ->
                        when (val result = parseVlessOutbound(outbound, index)) {
                            is LinkResult.Ok -> profiles += result.profile
                            is LinkResult.Bad -> failures += result.failure
                        }

                    else ->
                        failures +=
                            parseFailure(index, ParseFailureReason.UnknownScheme, "outbound protocol is not supported")
                }
            }

            else ->
                failures +=
                    parseFailure(index, ParseFailureReason.UnknownScheme, "outbound protocol is not supported")
        }
    }

    return ParseOutcome(profiles, failures)
}

@Suppress("ReturnCount")
private fun parseVlessOutbound(
    outbound: JsonObject,
    index: Int,
): LinkResult {
    val settings = outbound["settings"] as? JsonObject
    val vnext =
        (settings?.get("vnext") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return bad(index, ParseFailureReason.MalformedJson, "vless outbound has no vnext")

    val address =
        vnext.stringValue("address")?.takeIf { it.isNotBlank() }
            ?: return bad(index, ParseFailureReason.MalformedJson, "vless address is missing")
    val port =
        vnext.portValue("port")
            ?: return bad(index, ParseFailureReason.InvalidPort, "vless port is not a number")
    validatePort(port)?.let { return bad(index, ParseFailureReason.InvalidPort, it) }

    val user =
        (vnext["users"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return bad(index, ParseFailureReason.MissingCredential, "vless outbound has no user")
    val uuid =
        user.stringValue("id")
            ?: return bad(index, ParseFailureReason.MissingCredential, "vless UUID is missing")
    validateUuid(uuid)?.let { return bad(index, ParseFailureReason.MissingCredential, it) }

    val streamSettings = outbound["streamSettings"] as? JsonObject
    val network = streamSettings?.stringValue("network")?.takeIf { it.isNotBlank() } ?: "tcp"
    val security =
        parseSecurity(streamSettings, address)
            ?: return bad(index, ParseFailureReason.InvalidRealityKey, "reality public key is invalid")
    val flow = user.stringValue("flow")?.takeIf { it.isNotBlank() }
    val stream = StreamSettings(network = network, security = security)
    val vless = VlessOutbound(address, port, uuid, flow, stream)
    val name = outbound.stringValue("tag")?.takeIf { it.isNotBlank() } ?: address
    return LinkResult.Ok(Profile(profileId("vless", address, port, uuid), name, vless))
}

@Suppress("ReturnCount")
private fun parseSecurity(
    streamSettings: JsonObject?,
    address: String,
): Security? {
    return when (streamSettings?.stringValue("security")) {
        "reality" -> {
            val reality = streamSettings["realitySettings"] as? JsonObject
            val publicKey =
                reality?.stringValue("publicKey")
                    ?: return null
            validateRealityPublicKey(publicKey)?.let { return null }
            Security.Reality(
                serverName = reality.stringValue("serverName")?.takeIf { it.isNotBlank() } ?: address,
                publicKey = publicKey,
                shortId = reality.stringValue("shortId").orEmpty(),
                fingerprint = reality.stringValue("fingerprint")?.takeIf { it.isNotBlank() } ?: "chrome",
                spiderX = reality.stringValue("spiderX")?.takeIf { it.isNotBlank() } ?: "/",
            )
        }

        "tls" -> {
            val tls = streamSettings["tlsSettings"] as? JsonObject
            Security.Tls(
                serverName = tls?.stringValue("serverName")?.takeIf { it.isNotBlank() } ?: address,
                fingerprint = tls?.stringValue("fingerprint")?.takeIf { it.isNotBlank() } ?: "chrome",
                allowInsecure = tls?.booleanValue("allowInsecure") == true,
            )
        }

        else -> Security.None
    }
}

private fun bad(
    index: Int,
    reason: ParseFailureReason,
    detail: String,
): LinkResult.Bad = LinkResult.Bad(parseFailure(index, reason, detail))

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.stringContent()

private fun JsonPrimitive.stringContent(): String? = content.takeIf { isString }

private fun JsonObject.portValue(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
