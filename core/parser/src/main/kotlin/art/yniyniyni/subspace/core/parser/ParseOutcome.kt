// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.redact
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
 * Build these with [parseFailure], never with the constructor: [detail] must be
 * redacted, and redacting at construction rather than at the log call means no
 * code path can produce an unredacted instance and no reviewer has to check
 * every call site. Same rule M1 applied to `failure()` in `:core:model`.
 *
 * The constructor is genuinely `private` — not just a style note above it — so
 * `ParseFailure(index, reason, rawInput)` does not compile outside this class,
 * from any file, in or out of this module. [ConsistentCopyVisibility] closes the
 * matching hole in the generated `copy()`: on Kotlin 2.4, a data class with a
 * private constructor still gets a *public* `copy()` unless this annotation is
 * present, which would otherwise let `existingFailure.copy(detail = rawInput)`
 * rebuild an unredacted instance without ever calling [parseFailure]. Both were
 * verified empirically with a throwaway check compiled from a separate file —
 * see the Task 3 fix report.
 */
@ConsistentCopyVisibility
public data class ParseFailure private constructor(
    /**
     * Entry index, 0-based. Lets a user say "entry 143 failed" without pasting
     * entry 143 anywhere (§5.6).
     */
    val index: Int,
    val reason: ParseFailureReason,
    /** Redacted. See [parseFailure]. */
    val detail: String,
) {
    internal companion object {
        // Only reachable from parseFailure() below, which is the sole public
        // entry point. Internal (not private) because a private constructor is
        // scoped to this class body, and the class body is the only place that
        // can see it — this companion function is how parseFailure(), a
        // top-level function in the same file but not the same class, reaches it.
        internal fun redacted(
            index: Int,
            reason: ParseFailureReason,
            detail: String,
        ): ParseFailure = ParseFailure(index, reason, redact(detail))
    }
}

/**
 * The only way to build a [ParseFailure].
 *
 * **Write [detail] so it survives redaction.** `redact` collapses any URL to a
 * single token, so a detail that quotes the offending link becomes literally
 * "<redacted>" and tells the user nothing. Describe the problem instead:
 * "publicKey must be 43 characters, got 12" carries no secret and stays useful.
 */
public fun parseFailure(
    index: Int,
    reason: ParseFailureReason,
    detail: String,
): ParseFailure = ParseFailure.redacted(index, reason, detail)

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
