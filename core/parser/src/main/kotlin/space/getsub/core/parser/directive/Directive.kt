// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser.directive

/**
 * Which transport carried a directive.
 *
 * ARCHITECTURE.md §A.1: the same key set arrives either as HTTP response
 * headers or as `#`-prefixed lines in the subscription body, and both must be
 * supported. Kept on the value rather than discarded at the door because the
 * precedence rule between the two is
 * [unverified][DirectiveSplitter.split] — a diagnostic that says which
 * transport a value came from is what settles it against a real panel.
 */
public enum class DirectiveSource { Header, BodyLine }

/**
 * One `key: value` pair as it arrived, before the registry has seen it.
 *
 * [key] is lower-cased; [value] is trimmed. Nothing else has been done to
 * either — this is still hostile input (§A.1) and validation is
 * [DirectiveValidator]'s job.
 */
public data class RawDirective(
    val key: String,
    val value: String,
    val source: DirectiveSource,
)

/**
 * The result of separating directives from configs.
 *
 * @property remainingBody the body with directive lines removed, ready for
 *   `SubscriptionParser`. Never null and never pre-judged: §A.4.2 requires the
 *   client to sniff the actual format rather than assume one, so this is handed
 *   over unchanged.
 */
public data class SplitBody(
    val directives: List<RawDirective>,
    val remainingBody: String,
)
