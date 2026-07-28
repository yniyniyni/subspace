// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import java.security.MessageDigest

private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16

internal sealed interface LinkResult {
    data class Ok(
        val profile: Profile,
    ) : LinkResult

    data class Bad(
        val failure: ParseFailure,
    ) : LinkResult
}

/**
 * The result of parsing anything.
 *
 * ARCHITECTURE.md §7: one bad line in a 200-line subscription must not lose the
 * other 199. Both halves are always present — 197 profiles and 3 failures is a
 * normal, successful outcome, not an error state.
 */
public data class ParseOutcome(
    val profiles: List<Profile>,
    val failures: List<ParseFailure>,
) {
    public companion object {
        public val EMPTY: ParseOutcome = ParseOutcome(emptyList(), emptyList())
    }
}

public operator fun ParseOutcome.plus(other: ParseOutcome): ParseOutcome =
    ParseOutcome(profiles + other.profiles, failures + other.failures)

/** Builds a deterministic lowercase SHA-256 ID from the protocol identity material. */
internal fun profileId(
    discriminant: String,
    address: String,
    port: Int,
    credential: String,
): String {
    val material = "$discriminant|$address|$port|$credential"
    val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
        (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
    }
}

/**
 * One entry that could not be parsed.
 *
 * Build these with [parseFailure], never with the constructor. The detail is
 * a closed vocabulary, so there is no free-text channel for input to reach a
 * diagnostic.
 *
 * The constructor is genuinely `private`, and [ConsistentCopyVisibility]
 * closes the matching hole in generated `copy()` on Kotlin 2.4.
 */
@ConsistentCopyVisibility
public data class ParseFailure private constructor(
    /**
     * Entry index, 0-based. Lets a user say "entry 143 failed" without pasting
     * entry 143 anywhere (§5.6).
     */
    val index: Int,
    val reason: ParseFailureReason,
    val detail: FailureDetail,
) {
    internal companion object {
        internal fun of(
            index: Int,
            reason: ParseFailureReason,
            detail: FailureDetail,
        ): ParseFailure = ParseFailure(index, reason, detail)
    }
}

/**
 * The only way to build a [ParseFailure].
 *
 * [FailureDetail] has no free-text field, so construction cannot carry a
 * secret or user/config text into parser diagnostics.
 */
public fun parseFailure(
    index: Int,
    reason: ParseFailureReason,
    detail: FailureDetail,
): ParseFailure = ParseFailure.of(index, reason, detail)

/**
 * Why one entry failed.
 *
 * An enum, not a string: adding a reason is a deliberate change that every
 * consumer's `when` must acknowledge, exactly like `FailureReason` in M1.
 */
public enum class ParseFailureReason {
    UnknownScheme,
    MalformedUri,
    MissingCredential,
    InvalidPort,
    InvalidRealityKey,
    UnsupportedMethod,
    MalformedBase64,
    MalformedJson,
    MalformedYaml,

    /**
     * Nothing to take from the input: either it was blank, or it was a
     * well-formed container that held no server entries — `{}`, an `outbounds`
     * array with nothing supported in it, or a Clash file whose `proxies:` is
     * absent, null or empty. See the invariant on [SubscriptionParser.parse].
     */
    EmptyInput,
}
