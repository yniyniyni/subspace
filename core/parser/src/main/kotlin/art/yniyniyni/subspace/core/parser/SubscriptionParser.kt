// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

/**
 * The one public entry point of `:core:parser`.
 *
 * Callers do not choose a format. ARCHITECTURE.md §7 lists four container
 * shapes and a user pasting into a box knows which one they have roughly never,
 * so detection is this module's job.
 *
 * Never throws — §7's central rule. Everything else in this module is
 * `internal` so that stays true: there is exactly one way in, and it has
 * exactly one behaviour.
 */
public object SubscriptionParser {
    /**
     * @return profiles and failures together. 197 profiles and 3 failures is a
     *   normal outcome, not an error state.
     */
    public fun parse(raw: String): ParseOutcome {
        val text = raw.trim()
        if (text.isEmpty()) {
            return ParseOutcome(
                emptyList(),
                listOf(parseFailure(0, ParseFailureReason.EmptyInput, "nothing to parse")),
            )
        }

        // Order is load-bearing and pinned by a test.
        //
        // JSON must be checked before YAML: JSON is a subset of YAML, so a raw
        // Xray config parses cleanly as YAML. With the checks reversed, every
        // raw config would be routed to the Clash branch, fail on the missing
        // `proxies:` key, and be reported as malformed YAML — pointing the user
        // at entirely the wrong format.
        return when {
            text.startsWith("{") -> parseXrayJson(text)
            looksLikeClash(text) -> parseClashYaml(text)
            else -> {
                val decoded = decodeBase64Tolerant(text)
                if (decoded != null && decoded.contains("://")) {
                    parseLinkList(decoded)
                } else {
                    parseLinkList(text)
                }
            }
        }
    }
}

/**
 * A cheap textual check rather than a trial YAML parse.
 *
 * Parsing to decide whether to parse costs the work twice on every
 * subscription, and a trial parse also succeeds on plain link lists — YAML is
 * permissive enough to read them as scalars.
 */
private fun looksLikeClash(text: String): Boolean =
    text.lineSequence().any {
        it.trimEnd() == "proxies:" || it.startsWith("proxies:")
    }
