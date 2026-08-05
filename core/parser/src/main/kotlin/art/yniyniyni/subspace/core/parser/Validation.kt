// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import java.util.Base64

private val UUID_SHAPE =
    Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

/** REALITY public keys are X25519: 32 bytes, base64url, unpadded — 43 chars. */
private const val REALITY_KEY_LENGTH = 43
private const val REALITY_KEY_BYTES = 32
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private val BASE64URL_SHAPE = Regex("""^[A-Za-z0-9_-]+$""")

/**
 * Every Shadowsocks cipher this parser accepts.
 *
 * Widened from `internal` to `public` for Task 21: the profile editor
 * (`:feature:profiles`) validates a hand-edited Shadowsocks method against
 * this exact set, rather than maintaining a second list that could silently
 * drift from what [validateShadowsocksMethod] actually accepts — the editor
 * and the importer must not be able to disagree about what is valid.
 */
public val SHADOWSOCKS_METHODS: Set<String> =
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

/**
 * Whether [port] is a valid TCP/UDP port number.
 *
 * Public since Task 21 — the profile editor validates an edited port through
 * this exact function, the same one every share-link and Clash parser in
 * this file already calls, so the editor and the importer cannot disagree
 * about what is valid.
 */
public fun validatePort(port: Int): FailureDetail? =
    if (port in MIN_PORT..MAX_PORT) {
        null
    } else {
        FailureDetail.Range(DetailField.Port, MIN_PORT, MAX_PORT, port)
    }

/** Whether [uuid] is a well-formed VLESS/VMess credential. Public since Task 21 — see [validatePort]'s KDoc. */
public fun validateUuid(uuid: String): FailureDetail? =
    when {
        uuid.isEmpty() -> FailureDetail.Missing(DetailField.Uuid)
        UUID_SHAPE.matches(uuid) -> null
        else -> FailureDetail.Malformed(DetailField.Uuid)
    }

/** Whether [pbk] is a canonical REALITY X25519 public key. Public since Task 21 — see [validatePort]'s KDoc. */
public fun validateRealityPublicKey(pbk: String): FailureDetail? =
    when {
        pbk.isEmpty() -> FailureDetail.Missing(DetailField.PublicKey)
        pbk.length != REALITY_KEY_LENGTH ->
            FailureDetail.Length(DetailField.PublicKey, REALITY_KEY_LENGTH, pbk.length)
        !BASE64URL_SHAPE.matches(pbk) -> FailureDetail.Malformed(DetailField.PublicKey)
        !isCanonicalRealityPublicKey(pbk) -> FailureDetail.Malformed(DetailField.PublicKey)
        else -> null
    }

/** Whether [method] is one of [SHADOWSOCKS_METHODS]. Public since Task 21 — see [validatePort]'s KDoc. */
public fun validateShadowsocksMethod(method: String): FailureDetail? =
    if (method in SHADOWSOCKS_METHODS) null else FailureDetail.Unsupported(DetailField.Method)

private fun isCanonicalRealityPublicKey(pbk: String): Boolean =
    runCatching {
        val decoded = Base64.getUrlDecoder().decode(pbk)
        decoded.size == REALITY_KEY_BYTES &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == pbk
    }.getOrDefault(false)
