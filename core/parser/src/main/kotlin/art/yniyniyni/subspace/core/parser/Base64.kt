// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import java.util.Base64

// Base64 encodes 3 bytes as 4 chars; a group is always this wide.
private const val BASE64_GROUP_SIZE = 4

/**
 * Decodes base64 the way subscriptions actually ship it.
 *
 * ARCHITECTURE.md §7 names these: missing padding, URL-safe vs standard
 * alphabet, embedded whitespace, non-UTF-8 bytes. All four appear together in
 * the wild, so normalising is cheaper than trying decoders in sequence.
 *
 * @return the decoded text, or null if the input is not base64 at all. Never
 *   throws — §7's central rule.
 */
internal fun decodeBase64Tolerant(input: String): String? {
    val stripped = input.filterNot { it.isWhitespace() }

    // One alphabet, one decoder. Translating - _ to + / lets the standard
    // decoder handle both rather than guessing which variant we were sent.
    val normalised = stripped.replace('-', '+').replace('_', '/')
    return normalised.padToBase64Group()?.let(::decodeUtf8OrNull)
}

/**
 * Pads to a multiple of [BASE64_GROUP_SIZE] with `=`, or null if [this] is
 * empty or has a length that no valid base64 (padded or not) can have.
 */
private fun String.padToBase64Group(): String? {
    val remainder = length % BASE64_GROUP_SIZE
    return when {
        isEmpty() || remainder == 1 -> null
        else -> this + "=".repeat((BASE64_GROUP_SIZE - remainder) % BASE64_GROUP_SIZE)
    }
}

/**
 * getOrNull turns the decoder's IllegalArgumentException into the null this
 * module promises never to throw instead. toString(UTF_8) substitutes
 * U+FFFD for invalid sequences rather than throwing, which is what we want:
 * a subscription with one bad byte should lose that character, not the
 * whole batch.
 */
private fun decodeUtf8OrNull(padded: String): String? =
    runCatching {
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    }.getOrNull()
