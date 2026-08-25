// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser.directive

/**
 * A directive that did not survive validation.
 *
 * **Carries the key only, never the value** (§5.6). A rejected value is
 * precisely the hostile input this stage exists to stop, and echoing it into a
 * log or a diagnostic is the leak. The absence of a `value` field is the
 * guarantee; `DirectiveValidatorTest` pins it against a future widening.
 */
public data class DirectiveRejection(
    val key: String,
    val reason: RejectionReason,
)

/**
 * @property accepted canonicalised values, ready to persist. Keys are registry
 *   keys; nothing outside [DirectiveRegistry] can appear here.
 * @property rejections what was dropped and why, for logging and for the
 *   subscription detail screen's diagnostics.
 */
public data class ValidatedDirectives(
    val accepted: Map<String, String>,
    val rejections: List<DirectiveRejection>,
)

/**
 * ARCHITECTURE.md §A.1's third pipeline stage: validate directives against an
 * allow-list and schema **before** anything reaches storage.
 *
 * Never throws (§7). Every input produces a [ValidatedDirectives]; a response
 * consisting entirely of garbage yields an empty [ValidatedDirectives.accepted]
 * and a full [ValidatedDirectives.rejections], which is an answer, not an error.
 */
public object DirectiveValidator {
    public fun validate(directives: List<RawDirective>): ValidatedDirectives {
        val accepted = LinkedHashMap<String, String>()
        val rejections = mutableListOf<DirectiveRejection>()
        val seen = mutableSetOf<String>()

        directives.forEach { directive ->
            if (!seen.add(directive.key)) return@forEach

            val spec = DirectiveRegistry.spec(directive.key)
            when {
                spec == null ->
                    rejections += DirectiveRejection(directive.key, RejectionReason.UnknownKey)

                spec.disposition is Disposition.Reject ->
                    rejections += DirectiveRejection(directive.key, RejectionReason.CutKey)

                else ->
                    when (val result = spec.kind.canonicalise(directive.value)) {
                        is KindResult.Canonical -> accepted[spec.key] = result.value
                        is KindResult.Invalid ->
                            rejections += DirectiveRejection(spec.key, result.reason)
                    }
            }
        }

        return ValidatedDirectives(accepted, rejections)
    }
}
