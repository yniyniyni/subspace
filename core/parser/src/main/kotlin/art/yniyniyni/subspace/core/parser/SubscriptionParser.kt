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
     *
     * **Invariant: `profiles.isEmpty()` implies `failures.isNotEmpty()`.** An
     * outcome always says something. §10.4: the redacted failure detail is the
     * only diagnostic a user can hand back, and the UI shows the generic
     * "could not parse" message with no reason at all when there is no failure
     * to draw one from.
     *
     * Enforced here rather than in each format parser. `{}`, `{"foo":1}`, and a
     * Clash file whose `proxies:` is absent, null or empty are each a
     * well-formed container holding nothing, and each format parser is locally
     * right to report no failure for them — nothing *failed*. It is only at
     * this level, where the whole call must produce an answer, that the empty
     * result becomes something to report. One place, so a fifth container shape
     * inherits the invariant for free.
     */
    public fun parse(raw: String): ParseOutcome {
        val outcome = parse(raw, depth = 0)
        if (outcome.profiles.isNotEmpty() || outcome.failures.isNotEmpty()) return outcome

        // EmptyInput rather than a new constant: from the caller's side this is
        // the same answer as a blank paste — the input yielded no entries — and
        // the detail carries what distinguishes them. Deliberately says nothing
        // about *which* container it was: that is already ambiguous here (the
        // input may have been base64 that decoded into one), and guessing would
        // point the user at the wrong format (§10.4).
        return ParseOutcome(
            emptyList(),
            listOf(parseFailure(0, ParseFailureReason.EmptyInput, FailureDetail.None)),
        )
    }

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
                listOf(parseFailure(0, ParseFailureReason.EmptyInput, FailureDetail.None)),
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
                                    FailureDetail.Malformed(DetailField.Base64Body),
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
