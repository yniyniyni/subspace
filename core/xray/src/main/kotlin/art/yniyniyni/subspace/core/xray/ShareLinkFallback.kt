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
 * This is guarded structurally rather than by re-deriving which path fired:
 * every real share link requires a `scheme://` prefix (§6 lists the five
 * schemes this project supports), and base64 — standard or URL-safe alphabet —
 * never contains a `:` character, so no fragment of an encoded blob can ever
 * contain `://`. Requiring the candidate text to contain `://` before calling
 * libXray is therefore always safe: it can never suppress a genuine,
 * correctly-indexed share link (which always has `://`), and it can never let
 * a misindexed base64 fragment reach libXray. The residual case — a
 * misindexed line of Clash YAML or raw JSON that *itself* happens to contain
 * `://` (an unrelated URL field, for instance) — is not fully closed by this
 * guard; it is accepted as out of scope here because §7's container-shape
 * failures do not carry line numbers by design, and recovering them would
 * need a richer [ParseFailure] than `:core:parser` currently exposes.
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
    ): ParseOutcome {
        if (outcome.failures.isEmpty()) return outcome

        val lines = originalText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        val recovered = mutableListOf<Profile>()
        val stillFailed = mutableListOf<ParseFailure>()

        outcome.failures.forEach { failure ->
            val line = lines.getOrNull(failure.index)
            val json = if (line != null && looksLikeShareLink(line)) convertOrNull(line) else null
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
     * Every share link this project supports (§6: `vless`, `vmess`, `trojan`,
     * `ss`, `socks`) has a `scheme://` prefix, and base64 — standard or
     * URL-safe alphabet — never contains `:`. So this both filters out
     * hopeless input (saving a JNI call) and is the correctness guard
     * documented on the class: it is the only thing standing between a
     * misindexed base64 fragment and a JNI call that might, for all we know
     * about libXray's internals, do something with it.
     */
    private fun looksLikeShareLink(candidate: String): Boolean = candidate.contains("://")

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
