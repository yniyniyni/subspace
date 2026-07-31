// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.Outbound
import java.security.MessageDigest

private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16

/**
 * A stable identity for a `TYPED` profile, covering the **whole** outbound.
 *
 * `Profile.id` in `:core:parser` hashes protocol/address/port/credential only.
 * That is correct for re-import dedup and wrong as a persistence key: two
 * variants of one server differing solely in SNI or flow produce the same id
 * and collapse into one row at upsert.
 *
 * Determinism comes from kotlinx.serialization emitting properties in
 * declaration order, so the same outbound always encodes to the same bytes.
 */
internal fun identityHashOf(outbound: Outbound): String = sha256(outbound.toJson())

/**
 * Identity for a `RAW_JSON` profile: the hash of exactly what the user pasted.
 *
 * No canonicalisation, and none needed — §6 stores those bytes unmodified, so
 * whitespace and key order are part of what is being identified.
 */
internal fun identityHashOfRaw(rawJson: String): String = sha256(rawJson)

private fun sha256(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
        }
