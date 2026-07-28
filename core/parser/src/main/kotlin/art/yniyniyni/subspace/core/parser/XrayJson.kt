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
@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
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
                listOf(
                    parseFailure(
                        0,
                        ParseFailureReason.MalformedJson,
                        FailureDetail.Malformed(DetailField.JsonBody),
                    ),
                ),
            )

    val outbounds = root["outbounds"] as? JsonArray ?: return ParseOutcome.EMPTY
    // The root label, not an outbound field. `tag` is a routing identifier that
    // exporters set to "proxy" essentially always, so it makes a poor name;
    // `remarks` is where a client writes the share link's `#fragment` back out.
    // A config with several vless outbounds therefore names them all alike —
    // acceptable because profile identity is content-derived, not name-derived.
    val remarks = root.stringValue("remarks")?.takeIf { it.isNotBlank() }
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
                            parseFailure(
                                index,
                                ParseFailureReason.UnknownScheme,
                                FailureDetail.Unsupported(DetailField.Scheme),
                            )

                    "vless" ->
                        parseVlessOutbound(outbound, index, remarks).forEach { result ->
                            when (result) {
                                is LinkResult.Ok -> profiles += result.profile
                                is LinkResult.Bad -> failures += result.failure
                            }
                        }

                    else ->
                        failures +=
                            parseFailure(
                                index,
                                ParseFailureReason.UnknownScheme,
                                FailureDetail.Unsupported(DetailField.Scheme),
                            )
                }
            }

            else ->
                failures +=
                    parseFailure(
                        index,
                        ParseFailureReason.UnknownScheme,
                        FailureDetail.Unsupported(DetailField.Scheme),
                    )
        }
    }

    return ParseOutcome(profiles, failures)
}

@Suppress("ReturnCount")
private fun parseVlessOutbound(
    outbound: JsonObject,
    index: Int,
    remarks: String?,
): List<LinkResult> {
    val settings = outbound["settings"] as? JsonObject
    val vnext =
        settings?.get("vnext") as? JsonArray
            ?: return listOf(
                bad(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody)),
            )
    if (vnext.isEmpty()) {
        return listOf(
            bad(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody)),
        )
    }

    return vnext.flatMap { destination ->
        val vnextObject =
            destination as? JsonObject
                ?: return@flatMap listOf(
                    bad(index, ParseFailureReason.MalformedJson, FailureDetail.Malformed(DetailField.JsonBody)),
                )
        parseVlessDestination(outbound, vnextObject, index, remarks)
    }
}

@Suppress("ReturnCount")
private fun parseVlessDestination(
    outbound: JsonObject,
    vnext: JsonObject,
    index: Int,
    remarks: String?,
): List<LinkResult> {
    val address =
        vnext.stringValue("address")?.takeIf { it.isNotBlank() }
            ?: return listOf(
                bad(index, ParseFailureReason.MalformedJson, FailureDetail.Missing(DetailField.Address)),
            )
    val port =
        vnext.portValue("port")
            ?: return listOf(
                bad(index, ParseFailureReason.InvalidPort, FailureDetail.Malformed(DetailField.Port)),
            )
    validatePort(port)?.let { return listOf(bad(index, ParseFailureReason.InvalidPort, it)) }

    val users =
        vnext["users"] as? JsonArray
            ?: return listOf(
                bad(index, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Credential)),
            )
    if (users.isEmpty()) {
        return listOf(
            bad(index, ParseFailureReason.MissingCredential, FailureDetail.Missing(DetailField.Credential)),
        )
    }

    val streamSettings = outbound["streamSettings"] as? JsonObject
    val network = streamSettings?.stringValue("network")?.takeIf { it.isNotBlank() } ?: "tcp"
    val name =
        if (remarks != null) {
            remarks
        } else {
            outbound.stringValue("tag")?.takeIf { it.isNotBlank() } ?: address
        }
    return when (val parsedSecurity = parseSecurity(streamSettings, address)) {
        is SecurityParse.Bad -> users.map { bad(index, parsedSecurity.reason, parsedSecurity.detail) }
        is SecurityParse.Ok ->
            users.map { userElement ->
                val user =
                    userElement as? JsonObject
                        ?: return@map bad(
                            index,
                            ParseFailureReason.MissingCredential,
                            FailureDetail.Malformed(DetailField.Credential),
                        )
                val uuid =
                    user.stringValue("id")
                        ?: return@map bad(
                            index,
                            ParseFailureReason.MissingCredential,
                            FailureDetail.Missing(DetailField.Uuid),
                        )
                validateUuid(uuid)?.let { return@map bad(index, ParseFailureReason.MissingCredential, it) }
                val flow = user.stringValue("flow")?.takeIf { it.isNotBlank() }
                val stream = StreamSettings(network = network, security = parsedSecurity.security)
                val vless = VlessOutbound(address, port, uuid, flow, stream)
                LinkResult.Ok(Profile(profileId("vless", address, port, uuid), name, vless))
            }
    }
}

@Suppress("CyclomaticComplexMethod", "ReturnCount")
private fun parseSecurity(
    streamSettings: JsonObject?,
    address: String,
): SecurityParse {
    val securityElement = streamSettings?.get("security")
    if (securityElement == null || securityElement == JsonNull) {
        return SecurityParse.Ok(Security.None)
    }
    if (securityElement !is JsonPrimitive || !securityElement.isString) {
        return SecurityParse.Bad(
            ParseFailureReason.MalformedJson,
            FailureDetail.Malformed(DetailField.Security),
        )
    }

    return when (securityElement.content) {
        "reality" -> {
            val reality = streamSettings["realitySettings"] as? JsonObject
            val publicKey =
                reality?.stringValue("publicKey")
                    ?: return SecurityParse.Bad(
                        ParseFailureReason.InvalidRealityKey,
                        FailureDetail.Missing(DetailField.PublicKey),
                    )
            validateRealityPublicKey(publicKey)?.let {
                return SecurityParse.Bad(ParseFailureReason.InvalidRealityKey, it)
            }
            SecurityParse.Ok(
                Security.Reality(
                    serverName = reality.stringValue("serverName")?.takeIf { it.isNotBlank() } ?: address,
                    publicKey = publicKey,
                    shortId = reality.stringValue("shortId").orEmpty(),
                    fingerprint = reality.stringValue("fingerprint")?.takeIf { it.isNotBlank() } ?: "chrome",
                    spiderX = reality.stringValue("spiderX")?.takeIf { it.isNotBlank() } ?: "/",
                ),
            )
        }

        "tls" -> {
            val tls = streamSettings["tlsSettings"] as? JsonObject
            SecurityParse.Ok(
                Security.Tls(
                    serverName = tls?.stringValue("serverName")?.takeIf { it.isNotBlank() } ?: address,
                    fingerprint = tls?.stringValue("fingerprint")?.takeIf { it.isNotBlank() } ?: "chrome",
                    allowInsecure = tls?.booleanValue("allowInsecure") == true,
                ),
            )
        }

        "none" -> SecurityParse.Ok(Security.None)
        else ->
            SecurityParse.Bad(
                ParseFailureReason.MalformedJson,
                FailureDetail.Unsupported(DetailField.Security),
            )
    }
}

private sealed interface SecurityParse {
    data class Ok(
        val security: Security,
    ) : SecurityParse

    data class Bad(
        val reason: ParseFailureReason,
        val detail: FailureDetail,
    ) : SecurityParse
}

private fun bad(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
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
