// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.parser.ParseOutcome
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import org.json.JSONObject

/**
 * A second opinion on lines our parser could not read.
 *
 * `:core:parser` is deliberately lenient — §7 requires that one bad line in two
 * hundred does not lose the other 199, which is precisely what libXray will not
 * do, since it rejects a malformed batch wholesale. That leniency is why we
 * wrote our own parser rather than delegating.
 *
 * But the core understands share-link dialects we may not, so lines we failed
 * are worth one retry through it. **Opt-in, and failed lines only**: a
 * subscription that parses cleanly makes zero JNI calls.
 *
 * This lives in `:core:xray` rather than `:core:parser` because it needs the
 * AAR. Putting it in the parser would drag an Android dependency into a module
 * §4 requires to be pure JVM, and would turn every parser test into a device
 * test.
 *
 * ### The index-mapping limitation
 *
 * [ParseFailure.index] is only ever a line number when `:core:parser` actually
 * routed the input through its plain link-list path. `SubscriptionParser` is
 * `:core:parser`'s only public surface, so this object cannot ask which path
 * fired — [ParseOutcome] does not say. Concretely, indices mean something else
 * entirely for the other three container shapes:
 *
 * - A base64 subscription blob is decoded and re-fed through detection
 *   ([SubscriptionParser]'s single bounded re-entry). If the decoded text turns
 *   out to be another link list, its failure indices describe lines of the
 *   *decoded* text, not of [originalText]. If it turns out to be Clash YAML or
 *   raw Xray JSON, they are not line numbers at all.
 * - Raw Xray JSON failures index the `outbounds` array.
 * - Clash YAML failures index the `proxies` list.
 *
 * Blindly doing `originalText.lines()[failure.index]` in those cases hands
 * libXray an unrelated fragment of [originalText] under the failed line's
 * index — at best noise, at worst (per ARCHITECTURE.md §10.5, no upstream
 * behaviour may be assumed) a fragment that libXray happens to accept and
 * converts into a profile that has nothing to do with the entry that actually
 * failed. That would be a correctness bug, not a missed recovery.
 *
 * Two guards close this, and both are needed:
 *
 * 1. [retry] skips line-based recovery **entirely** when [originalText] itself
 *    is container-shaped — starts with `{` (raw Xray JSON) or contains a
 *    `proxies:` line (Clash YAML) — using the same detection order
 *    `SubscriptionParser` uses, so indices are never even looked up against
 *    text they cannot describe. This is what closes the case a line-based
 *    guard alone cannot: a pretty-printed, multi-line JSON or YAML document
 *    where the line that happens to sit at the failure's numeric offset
 *    contains an unrelated `://` (an `sni`, a `providers:` URL, a comment).
 * 2. For the remaining case — [originalText] was not container-shaped at the
 *    top level but decoded from base64 into something that *is* (or into
 *    another link list, indexed against the decoded text rather than
 *    [originalText]) — the per-line guard below still applies: no candidate
 *    line reaches libXray unless it contains `://`. Every real share link
 *    this project supports (§7: `vless`, `vmess`, `trojan`, `ss`, `socks`)
 *    requires a `scheme://` prefix, and base64 — standard or URL-safe
 *    alphabet — never contains a `:` character, so no fragment of an encoded
 *    blob can ever satisfy it. That guard can never suppress a genuine,
 *    correctly-indexed share link, and can never let a misindexed base64
 *    fragment through.
 */
public object ShareLinkFallback {
    /**
     * @param originalText the text originally handed to [SubscriptionParser].
     * @param outcome that parser's result.
     * @return an outcome where any line the core could read has become a
     *   profile. Lines neither could read keep their original failure.
     */
    public fun retry(
        originalText: String,
        outcome: ParseOutcome,
    ): ParseOutcome = retry(originalText, outcome, ::convertOrNull)

    /**
     * Test seam. [convert] defaults to [convertOrNull] — the real libXray
     * call — through the public two-argument [retry] above.
     *
     * `:core:xray`'s JVM unit tests cannot exercise the real libXray call at
     * all (`org.json.JSONObject` is an unmocked Android stub outside
     * `androidTest`), and asserting on [retry]'s *return value* cannot tell
     * the two guards below apart from each other: both a container-shaped
     * input and a plain "://"-free line produce an unchanged outcome for
     * different reasons. Taking the converter as a parameter lets a JVM test
     * substitute a spy and assert on whether it was *called*, which is the
     * only way to prove which guard is actually doing the suppressing.
     * Internal, not public: [SubscriptionParser.parse] stays the only public
     * API of `:core:parser`, and the two-argument [retry] stays the only
     * public entry point here.
     */
    internal fun retry(
        originalText: String,
        outcome: ParseOutcome,
        convert: (String) -> String?,
    ): ParseOutcome {
        // Second condition: see "The index-mapping limitation" above. A
        // container-shaped input's failure indices are never line numbers, so
        // there is nothing safe to look up — return the outcome unchanged
        // rather than guess.
        if (outcome.failures.isEmpty() || isContainerShaped(originalText)) return outcome

        val lines = originalText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        val recovered = mutableListOf<Profile>()
        val stillFailed = mutableListOf<ParseFailure>()

        outcome.failures.forEach { failure ->
            val line = lines.getOrNull(failure.index)
            val json = if (line != null && looksLikeShareLink(line)) convert(line) else null
            val reparsed = if (json == null) null else SubscriptionParser.parse(json)

            if (reparsed != null && reparsed.profiles.isNotEmpty()) {
                recovered += reparsed.profiles
            } else {
                stillFailed += failure
            }
        }

        return ParseOutcome(outcome.profiles + recovered, stillFailed)
    }

    /**
     * Every share link this project supports (§7: `vless`, `vmess`, `trojan`,
     * `ss`, `socks`) has a `scheme://` prefix, and base64 — standard or
     * URL-safe alphabet — never contains `:`. So this both filters out
     * hopeless input (saving a JNI call) and is the second correctness guard
     * documented on the class: it is what stands between a misindexed base64
     * fragment and a JNI call that might, for all we know about libXray's
     * internals, do something with it.
     */
    private fun looksLikeShareLink(candidate: String): Boolean = candidate.contains("://")

    /**
     * Mirrors the detection `SubscriptionParser.parse` uses internally (raw
     * Xray JSON starts with `{`; Clash YAML has a `proxies:` line) to decide
     * whether [ParseFailure.index] can possibly be a line number of the text
     * being tested.
     *
     * Duplicated rather than shared: the real check
     * (`looksLikeClash`/`text.startsWith("{")` in `:core:parser`'s
     * `SubscriptionParser.kt`) is `internal` to that module and not visible
     * here, and neither widening `:core:parser`'s public API nor pulling this
     * module's Android dependency into it (§4 requires `:core:parser` stay
     * pure JVM) is acceptable just to share one predicate. **This must move in
     * lockstep with `SubscriptionParser`'s detection** — if that changes,
     * update this to match, or the container-shape skip below silently stops
     * covering an input shape it used to.
     */
    private fun isContainerShaped(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("{") || looksLikeClashYaml(trimmed)
    }

    private fun looksLikeClashYaml(text: String): Boolean =
        text.lineSequence().any { it.trimEnd() == "proxies:" || it.startsWith("proxies:") }

    /**
     * §5.6: `ShareLinkConverter`'s own KDoc warns that the core's error message
     * can quote the config. Nothing from this path reaches a log unredacted,
     * and the exception itself is swallowed — a failed second opinion is not an
     * error, it is the expected outcome for genuinely malformed input.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun convertOrNull(line: String): String? {
        // Request field is `text`, per docs/agent/research/libxray-api.md:77
        // (`convertShareLinksToXrayJson | ConvertShareLinksToXrayJsonRequest |
        // text | xray JSON`). LibXrayInvoke.call already unwraps the response's
        // `data` envelope (see its KDoc and XrayController's other call sites),
        // so what it returns here already *is* the xray JSON — no further
        // unwrap. Failure arrives as a thrown XrayException, not a null return.
        val payload = JSONObject().put("text", line)
        return try {
            LibXrayInvoke.call("convertShareLinksToXrayJson", payload)?.toString()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
