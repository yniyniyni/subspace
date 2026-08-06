// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.directive

private const val BOOL_TRUE = "true"
private const val BOOL_FALSE = "false"

/** Why a directive value did not survive validation. Never carries the value itself (§5.6). */
public enum class RejectionReason {
    /** Not in the registry at all. Ignored and logged (§A.1). */
    UnknownKey,

    /** In the registry with an explicit rejection — Appendix D's cut list. */
    CutKey,

    /** Present but empty. Distinct from absent, and worth reporting. */
    BlankValue,

    /** Outside the documented range. Never clamped (spec §8). */
    OutOfRange,

    NotAnInteger,
    TooLong,
    NotAnEnumMember,
    MalformedUrl,
}

/** The outcome of canonicalising one value against its kind. */
public sealed interface KindResult {
    /** The value, in the single form that gets stored. */
    public data class Canonical(
        val value: String,
    ) : KindResult

    public data class Invalid(
        val reason: RejectionReason,
    ) : KindResult
}

/**
 * The shape of a directive's value.
 *
 * This is where every actual parsing rule lives, once each — spec D7's whole
 * argument. ~90 keys share these six kinds, so a bug in range checking is one
 * bug in one place rather than ninety opportunities for one.
 */
public sealed interface DirectiveKind {
    /**
     * ARCHITECTURE.md §A.1: `true`/`1` enable; **any other non-empty value
     * disables.** Canonicalises to `"true"` or `"false"`.
     */
    public data object Bool : DirectiveKind

    /** A whole number within an inclusive documented range. */
    public data class Integer(
        val min: Int,
        val max: Int,
    ) : DirectiveKind

    /**
     * Free text with a documented maximum length.
     *
     * @property base64Allowed several keys are documented as "plain or base64".
     *   When set, a value that decodes to valid UTF-8 text is decoded; anything
     *   else is kept verbatim. [maxLength] is then checked against the
     *   **decoded** value, which is the one the user sees.
     */
    public data class Text(
        val maxLength: Int,
        val base64Allowed: Boolean,
    ) : DirectiveKind

    /** A closed set of documented values, matched case-insensitively. */
    public data class Enumerated(
        val values: Set<String>,
    ) : DirectiveKind

    /**
     * An absolute `http` or `https` URL.
     *
     * Scheme-restricted deliberately: §A.1 lists `new-url`, `fallback-url`,
     * `Geoipurl` and `Geositeurl` as attacker-controlled URLs in the threat
     * model, and a `file:` or `javascript:` value reaching any consumer is a
     * failure this validator exists to prevent.
     */
    public data object Url : DirectiveKind

    /** A comma-separated list. Canonicalises to trimmed entries, empties dropped. */
    public data object Csv : DirectiveKind
}

/**
 * Validates and canonicalises [value] against this kind. Never throws (§7).
 *
 * Each kind's rule is extracted to its own private function rather than
 * inlined in the `when` below: detekt's `CyclomaticComplexMethod` (threshold
 * 15) flagged the single-function form at complexity 20 — six independent
 * rules in one branch body accumulate decision points fast. Splitting them
 * out is a pure extraction: every branch below calls exactly the logic that
 * used to sit inline, unchanged.
 */
public fun DirectiveKind.canonicalise(value: String): KindResult {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return KindResult.Invalid(RejectionReason.BlankValue)

    return when (this) {
        DirectiveKind.Bool -> canonicaliseBool(trimmed)
        is DirectiveKind.Integer -> canonicaliseInteger(trimmed, min, max)
        is DirectiveKind.Text -> canonicaliseText(trimmed, maxLength, base64Allowed)
        is DirectiveKind.Enumerated -> canonicaliseEnumerated(trimmed, values)
        DirectiveKind.Url -> canonicaliseUrl(trimmed)
        DirectiveKind.Csv -> canonicaliseCsv(trimmed)
    }
}

private fun canonicaliseBool(trimmed: String): KindResult.Canonical =
    KindResult.Canonical(
        if (trimmed.equals(BOOL_TRUE, ignoreCase = true) || trimmed == "1") {
            BOOL_TRUE
        } else {
            BOOL_FALSE
        },
    )

private fun canonicaliseInteger(
    trimmed: String,
    min: Int,
    max: Int,
): KindResult {
    val parsed = trimmed.toIntOrNull()
    return when {
        parsed == null -> KindResult.Invalid(RejectionReason.NotAnInteger)
        parsed < min || parsed > max -> KindResult.Invalid(RejectionReason.OutOfRange)
        else -> KindResult.Canonical(parsed.toString())
    }
}

private fun canonicaliseText(
    trimmed: String,
    maxLength: Int,
    base64Allowed: Boolean,
): KindResult {
    val decoded = if (base64Allowed) decodeBase64Text(trimmed) ?: trimmed else trimmed
    return if (decoded.length > maxLength) {
        KindResult.Invalid(RejectionReason.TooLong)
    } else {
        KindResult.Canonical(decoded)
    }
}

private fun canonicaliseEnumerated(
    trimmed: String,
    values: Set<String>,
): KindResult {
    val lowered = trimmed.lowercase()
    return if (lowered in values) {
        KindResult.Canonical(lowered)
    } else {
        KindResult.Invalid(RejectionReason.NotAnEnumMember)
    }
}

private fun canonicaliseUrl(trimmed: String): KindResult =
    if (isHttpUrl(trimmed)) {
        KindResult.Canonical(trimmed)
    } else {
        KindResult.Invalid(RejectionReason.MalformedUrl)
    }

private fun canonicaliseCsv(trimmed: String): KindResult {
    val entries = trimmed.split(',').map(String::trim).filter(String::isNotEmpty)
    return if (entries.isEmpty()) {
        KindResult.Invalid(RejectionReason.BlankValue)
    } else {
        KindResult.Canonical(entries.joinToString(","))
    }
}

/**
 * The decoded text, or null when [value] is not base64 carrying valid UTF-8.
 *
 * Conservative on purpose. A short plain name can be accidentally valid base64,
 * so decoding is accepted only when the result is printable text — otherwise a
 * provider sending a plain title gets it mangled into mojibake.
 *
 * Written with a single `return` (detekt's `ReturnCount` caps functions at two,
 * and the guard-clause form this started from used five) via a chain of
 * [runCatching], [String.let], and [String.takeIf] rather than early exits;
 * each step short-circuits to `null` exactly where the corresponding guard
 * clause would have returned.
 */
private fun decodeBase64Text(value: String): String? {
    val bytes =
        runCatching {
            java.util.Base64
                .getMimeDecoder()
                .decode(value)
        }.getOrNull()
    return bytes?.takeIf { it.isNotEmpty() }?.let { nonEmpty ->
        val decoded = nonEmpty.toString(Charsets.UTF_8)
        decoded.takeIf { text ->
            text.toByteArray(Charsets.UTF_8).size == nonEmpty.size &&
                text.none { it.isISOControl() && it != '\n' && it != '\t' }
        }
    }
}

/**
 * Whether [value] is an absolute `http`/`https` URL with a host.
 *
 * Single-`return` form for the same `ReturnCount` reason as [decodeBase64Text]:
 * `uri` and `scheme` are computed unconditionally (both null-safe), and the
 * three original guard conditions are ANDed together in the one return
 * expression, short-circuiting in the same order the guard clauses would have.
 */
private fun isHttpUrl(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    return uri != null && (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}
