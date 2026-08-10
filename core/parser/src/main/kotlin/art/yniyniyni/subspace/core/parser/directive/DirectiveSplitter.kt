// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.directive

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
 * `#key: value` at the start of a line, or null.
 *
 * Line-leading only. A `#` mid-line is a share link's fragment
 * (`vless://…#My%20Server`) and consuming it would destroy the server's name.
 * A `#` with no colon after it is an ordinary comment and stays in the body.
 */
private fun String.asBodyDirective(): RawDirective? {
    val trimmed = trimStart()
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
