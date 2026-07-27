// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

// A base64-encoded subscription is many characters wide even for a single
// short link, so a run this long with none of the shapes below is far more
// likely to be a broken blob than ordinary short text. See looksLikeBlob.
private const val MIN_BLOB_LENGTH = 24

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
    public fun parse(raw: String): ParseOutcome = parse(raw, depth = 0)

    /**
     * @param depth 0 on the original call, 1 once a base64 decode has already
     *   re-entered detection once. Bounds re-entry to exactly one extra pass:
     *   a base64 blob that itself decodes to more base64 is not chased
     *   further, so this can never loop and never costs more than two passes
     *   over the input.
     */
    private fun parse(
        raw: String,
        depth: Int,
    ): ParseOutcome {
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
                when {
                    // Decode failed, but the shape says this was meant to be a
                    // blob, not a link. Naming it a malformed link ("entry is
                    // not a share link") would point the user at the wrong
                    // artifact entirely — they pasted a broken subscription,
                    // not a broken link.
                    decoded == null && looksLikeBlob(text) ->
                        ParseOutcome(
                            emptyList(),
                            listOf(
                                parseFailure(
                                    0,
                                    ParseFailureReason.MalformedBase64,
                                    "input looks like a base64 subscription but did not decode",
                                ),
                            ),
                        )
                    // Decode succeeded: re-enter detection on the decoded text
                    // rather than assuming it is a link list. Some providers
                    // base64 the whole body regardless of the inner format, so
                    // the decoded text can just as easily be Clash YAML or raw
                    // Xray JSON. Bounded to one re-entry by the depth check.
                    decoded != null && depth == 0 -> parse(decoded, depth = 1)
                    else -> parseLinkList(text)
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

/**
 * Whether [text] has the shape of a subscription blob that failed to decode,
 * rather than ordinary text that simply is not a link.
 *
 * Deliberately conservative, per ARCHITECTURE.md §10.4: this decides between
 * two user-facing messages, and a false positive here — labelling ordinary
 * garbage a "malformed base64 subscription" — is a worse diagnosis than the
 * generic one. Real base64 subscriptions are one long unbroken run with no
 * embedded "://"; anything short, or split across whitespace or newlines, is
 * routed to the link-list path instead, where a plain "not a share link"
 * failure is the more honest answer.
 */
private fun looksLikeBlob(text: String): Boolean =
    text.length >= MIN_BLOB_LENGTH && text.none { it.isWhitespace() } && !text.contains("://")
