// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser.directive

/**
 * Separates directives from server configs (ARCHITECTURE.md §A.1's first
 * pipeline stage).
 *
 * Never throws — §7's rule applies to this package as it does to the rest of
 * `:core:parser`. A body of any shape produces a [SplitBody]; whether it holds
 * anything useful is the caller's question.
 */
public object DirectiveSplitter {
    /**
     * @param headers HTTP response headers, keyed however the client supplied
     *   them. Matched case-insensitively.
     * @param body the raw subscription body.
     *
     * **Precedence: a header wins over a `#`-line carrying the same key.**
     *
     * **Verified on device during M4** (2026-08-09) and recorded in ARCHITECTURE.md §A.3.5. It was
     * chosen as a guess — the upstream documentation lists both transports for every key and
     * states no precedence, and header-wins was reasoned from a panel's headers being set by the
     * panel itself while the body is usually template output — and it was then confirmed
     * end to end against a real panel serving the same key on both transports with different
     * values. The boundary the check established: it holds per key, so a key present only in the
     * body is still read from the body even when other keys arrive as headers.
     *
     * Within the body alone, the **first** occurrence of a key wins, so a
     * template appending rather than replacing does not silently flip a value.
     */
    public fun split(
        headers: Map<String, String>,
        body: String,
    ): SplitBody {
        val seen = mutableSetOf<String>()
        val directives = mutableListOf<RawDirective>()

        headers.forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase()
            if (key.isNotEmpty() && seen.add(key)) {
                directives += RawDirective(key, rawValue.trim(), DirectiveSource.Header)
            }
        }

        val remaining = mutableListOf<String>()
        body.lineSequence().forEach { line ->
            val directive = line.asBodyDirective()
            when {
                directive == null -> remaining += line
                // A body line whose key a header already supplied is consumed,
                // not retained: it *was* a directive line, so leaving it in the
                // body would hand SubscriptionParser a line it must then fail on.
                !seen.add(directive.key) -> Unit
                else -> directives += directive
            }
        }

        return SplitBody(directives, remaining.joinToString("\n"))
    }
}

/**
 * The schemes a routing deeplink can arrive under, and the only path under them
 * this consumes. Mirrors `RoutingProfileImport`'s own accepted forms — kept as
 * literals here rather than imported so `:core:parser`'s directive layer does
 * not depend on its routing layer for a prefix test.
 */
private val ROUTING_LINK_PREFIXES = listOf("happ://routing/", "subspace://routing/")

/**
 * A bare `happ://routing/…` line, as the `routing` directive, or null.
 *
 * The routing directive is the one key a provider can deliver in a body without
 * a `#key:` prefix, because the deeplink *is* the value. Recognising it here is
 * what stops it reaching `SubscriptionParser`, which would try to read it as a
 * server (§A.1) — and consuming it is the point: leaving a recognised directive
 * line in the body is the exact defect that rule exists for.
 *
 * [trimmed] must already be `trimStart()`ed, so this matches line-leading only:
 * a `happ://routing/` appearing mid-line is inside a share link's fragment
 * (`vless://…#happ://routing/x`), where consuming it would destroy the server's
 * name — the same trap [asBodyDirective] documents for `#`.
 *
 * The value is passed through verbatim and never decoded here. §5.6: the
 * payload is the user's routing rules, and the directive layer neither reads
 * nor logs it.
 */
private fun asRoutingLinkDirective(trimmed: String): RawDirective? {
    val matches = ROUTING_LINK_PREFIXES.any { prefix -> trimmed.startsWith(prefix, ignoreCase = true) }
    return if (matches) RawDirective("routing", trimmed.trimEnd(), DirectiveSource.BodyLine) else null
}

// ReturnCount: the routing-link form and the `#key:` form are two distinct
// recognitions of the same line, each with its own early exit. Nesting them
// would hide that a line matching neither stays in the body.

/**
 * `#key: value` at the start of a line, or null.
 *
 * Line-leading only. A `#` mid-line is a share link's fragment
 * (`vless://…#My%20Server`) and consuming it would destroy the server's name.
 * A `#` with no colon after it is an ordinary comment and stays in the body.
 */
@Suppress("ReturnCount")
private fun String.asBodyDirective(): RawDirective? {
    val trimmed = trimStart()
    asRoutingLinkDirective(trimmed)?.let { return it }
    if (!trimmed.startsWith("#")) return null

    val separator = trimmed.indexOf(':')
    val key = if (separator > 1) trimmed.substring(1, separator).trim().lowercase() else ""
    val isValidKey = key.isNotEmpty() && key.none { it.isWhitespace() }

    return if (isValidKey) {
        RawDirective(key, trimmed.substring(separator + 1).trim(), DirectiveSource.BodyLine)
    } else {
        null
    }
}
