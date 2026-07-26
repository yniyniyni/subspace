// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

private val UUID_SHAPE =
    Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

/** REALITY public keys are X25519: 32 bytes, base64url, unpadded — 43 chars. */
private const val REALITY_KEY_LENGTH = 43
private const val MAX_PORT = 65_535
private const val INVALID_UUID_MESSAGE = "id is not a well-formed UUID"
private val BASE64URL_SHAPE = Regex("""^[A-Za-z0-9_-]+$""")

internal val SHADOWSOCKS_METHODS: Set<String> =
    setOf(
        "aes-128-gcm",
        "aes-192-gcm",
        "aes-256-gcm",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
        "none",
        "plain",
    )

internal fun validatePort(port: Int): String? = if (port in 1..MAX_PORT) null else invalidPortMessage(port)

internal fun validateUuid(uuid: String): String? = if (UUID_SHAPE.matches(uuid)) null else INVALID_UUID_MESSAGE

internal fun validateRealityPublicKey(pbk: String): String? =
    when {
        pbk.isEmpty() -> "publicKey (pbk) is missing"
        pbk.length != REALITY_KEY_LENGTH ->
            "publicKey (pbk) must be $REALITY_KEY_LENGTH characters, got ${pbk.length}"
        !BASE64URL_SHAPE.matches(pbk) -> "publicKey (pbk) must be unpadded base64url"
        else -> null
    }

internal fun validateShadowsocksMethod(method: String): String? =
    if (method in SHADOWSOCKS_METHODS) null else "unsupported Shadowsocks method"

private fun invalidPortMessage(port: Int): String = "port must be 1..$MAX_PORT, got $port"
