// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import java.security.MessageDigest
import java.util.Base64

/**
 * The device identifier sent as `x-hwid` on subscription requests.
 *
 * ARCHITECTURE.md §A.4.1: when a provider enables the device limit the client
 * **must** send this header — without it the subscription cannot be added or refreshed at all,
 * and there is no graceful degradation. The refusal arrives as a 200 with an empty body and the
 * marker headers, not as an error status; this comment used to say 404, which is the belief that
 * cost M4 a defect. Behind an interface so tests and the fetcher never touch `Settings.Secure`
 * directly.
 */
public fun interface HwidProvider {
    public fun hwid(): String
}

/**
 * Base64url-unpadded SHA-256 of [androidId].
 *
 * **The encoding is constrained by the panel, not chosen for taste.**
 * `remnawave/panel` requires `x-hwid` to match `/^[a-zA-Z0-9=-]{10,64}$/`, which
 * rules out standard base64 — its alphabet includes `+` and `/`. Base64url
 * yields 43 characters and clears the bound with headroom; hex would be 64,
 * exactly at the maximum with none.
 *
 * Hashed rather than sent raw so the platform identifier never leaves the
 * device (§A.4.1). `ANDROID_ID` is the input rather than a generated UUID
 * because it survives reinstall: a UUID that changes on reinstall silently
 * burns a slot from the user's device limit every time they reinstall.
 *
 * An empty [androidId] still produces a valid, stable identifier — the hash of
 * the empty string. Every device in that state shares it, which is the correct
 * failure: the provider sees one device rather than a stream of new ones.
 *
 * `Base64.getUrlEncoder()` alone is not sufficient: RFC 4648 §5's alphabet
 * substitutes `-` and `_` for standard base64's `+` and `/`, and the panel
 * pattern permits `-` but **not** `_` — `HwidTest`'s
 * `` `a derived hwid matches the panel's required pattern` `` catches this for
 * real digests, not just in theory. `_` is remapped to `=`, which the pattern
 * does allow; unlike stripping or replacing with an already-used character,
 * this keeps the mapping from the 64 base64 symbols to output characters
 * injective, so the encoding stays collision-free.
 */
internal fun deriveHwid(androidId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).replace('_', '=')
}
