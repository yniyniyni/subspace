// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.redact

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

/**
 * One entry that could not be parsed.
 *
 * Build these with [parseFailure], never with the constructor: [detail] must be
 * redacted, and redacting at construction rather than at the log call means no
 * code path can produce an unredacted instance and no reviewer has to check
 * every call site. Same rule M1 applied to `failure()` in `:core:model`.
 */
public data class ParseFailure(
    /**
     * Entry index, 0-based. Lets a user say "entry 143 failed" without pasting
     * entry 143 anywhere (§5.6).
     */
    val index: Int,
    val reason: ParseFailureReason,
    /** Redacted. See [parseFailure]. */
    val detail: String,
)

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
): ParseFailure = ParseFailure(index, reason, redact(detail))

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
    EmptyInput,
}
