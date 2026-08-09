// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.directive

private const val FIELD_SEPARATOR = ';'
private const val KEY_VALUE_SEPARATOR = '='
private const val KEY_UPLOAD = "upload"
private const val KEY_DOWNLOAD = "download"
private const val KEY_TOTAL = "total"
private const val KEY_EXPIRE = "expire"

/**
 * The parsed `subscription-userinfo` directive (ARCHITECTURE.md §A.1): a
 * provider's quota/usage counters and expiry, in bytes and epoch seconds.
 *
 * Every field is independently nullable because the provider sends whichever
 * fields it wants — "the provider did not say" and "the provider said zero"
 * are different facts (see [isUnlimited]), and a field this class did not
 * receive must never be rendered as if it had.
 */
public data class UserInfo(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    val expiresAtEpochSeconds: Long?,
) {
    /**
     * `total=0` is the documented convention for "no cap", not "zero bytes
     * remaining" — a quota bar rendering 100% used on an unlimited plan is
     * worse than rendering nothing, so callers check this before drawing one.
     */
    public val isUnlimited: Boolean get() = total == 0L

    /** [upload] plus [download], treating either absent counter as zero. */
    public val usedBytes: Long get() = (upload ?: 0) + (download ?: 0)
}

/**
 * Parses a raw `subscription-userinfo` header/directive value — hostile,
 * provider-controlled input (§7's never-throw philosophy applies): missing
 * fields, garbage values, negative numbers, values exceeding [Long], and a
 * field present but empty are all defined behaviour, never an exception.
 *
 * Semicolon-separated `key=value` pairs, e.g.
 * `"upload=0; download=2153701362; total=0; expire=1790951622"`. Each value
 * is parsed with [String.toLongOrNull]; a negative or unparseable value is
 * dropped rather than kept — one bad field must not fail the whole header,
 * and a dropped field reads the same as one the provider never sent.
 *
 * @return `null` when no field parsed at all (an empty or wholly garbage
 *   header carries no information), otherwise a [UserInfo] whose individual
 *   fields are `null` wherever the provider's value was absent, negative, or
 *   unparseable.
 */
public fun parseUserInfo(raw: String): UserInfo? {
    val fields = raw.split(FIELD_SEPARATOR).mapNotNull { it.toUserInfoFieldOrNull() }.toMap()
    return if (fields.isEmpty()) {
        null
    } else {
        UserInfo(
            upload = fields[KEY_UPLOAD],
            download = fields[KEY_DOWNLOAD],
            total = fields[KEY_TOTAL],
            expiresAtEpochSeconds = fields[KEY_EXPIRE],
        )
    }
}

/** One `key=value` segment, or `null` if it is blank, key-less, or its value is negative/unparseable. */
private fun String.toUserInfoFieldOrNull(): Pair<String, Long>? {
    val parts = split(KEY_VALUE_SEPARATOR, limit = 2)
    val key = parts.getOrNull(0)?.trim()?.lowercase()
    val value = parts.getOrNull(1)?.trim()?.toLongOrNull()
    return if (!key.isNullOrEmpty() && value != null && value >= 0) key to value else null
}
