// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// TooManyFunctions: the ICU-soundness fix added three small, independently
// tested gate predicates (hasNonAscii, keyedHostGate, labelledHostGate)
// alongside the existing pattern/gate pairs. Each is a single necessary
// condition kept separate so RedactionGateTest can pin it directly; merging
// them back to satisfy a count would hide the exact thing under test.
@file:Suppress("TooManyFunctions")

package space.getsub.core.model

private const val REDACTED = "<redacted>"

// A sentinel no pattern below can match, so an already-redacted string survives
// a second pass unchanged. See the note on idempotence in [redact].
internal const val SENTINEL = "R"

// The pattern vals and pipeline helpers below are `internal` rather than
// `private` solely so RedactionBenchmark.kt (same module, `:core:model`'s
// test source set, which Gradle's Kotlin plugin compiles as a friend of
// `main`) can time each pattern's pass in isolation (M8.5 spec §3.2, Task 21).
// No behaviour changes with the widened visibility — RedactionOracleTest and
// RedactionGateTest still pass unchanged.
internal val UUID_PATTERN =
    Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
internal val URL_PATTERN = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
internal val IPV4_PATTERN = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
internal val HOSTNAME_PATTERN = Regex("""\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b""")

/**
 * A message that is **nothing but** a `, `-separated list of geo filenames.
 *
 * This is the exact shape of `FailureReason.GeoDataMissing`'s detail, which
 * `TunnelService.resolveRouting` builds as `missing.sorted().joinToString(", ")`
 * over names [SAFE_GEO_FILE_NAME] has already accepted. §10.4 is the reason it
 * must survive [redact]: telling a user "geo data missing — `<redacted>`" sends
 * them looking for a broken server instead of re-downloading a file.
 *
 * ## Why the whole string, and not the token
 *
 * Exempting any *token* that looks like `<name>.dat` would exempt it everywhere,
 * in every string that ever reaches [redact] — including the config libXray
 * quotes back when `testXray` rejects one. `RoutingEntries` accepts any domain
 * body without whitespace, `/` or `:`, so `corp.internal.dat` is a legal routing
 * rule, and a routing rule is user browsing data under ARCHITECTURE.md §5.6. Anchoring to the
 * whole message means the exemption can only ever apply to a string that
 * contains nothing else — a config dump with a `.dat`-suffixed rule in it is not
 * one, and is redacted normally.
 *
 * Idempotent by construction: the input contains no [REDACTED] marker and the
 * output is the input, so a second pass — which `ConnectionStateParcel` performs
 * on the far side of the binder — returns the same string again.
 *
 * ## What this does not close
 *
 * A message that is *only* a `.dat`-suffixed hostname is passed through, because
 * a custom geo source may legitimately be called `corp.internal.dat` and this
 * grammar cannot distinguish the two. That residual is bounded by callers rather
 * than by the pattern: every diagnostic reaching [redact] carries prose,
 * punctuation or JSON around its tokens, and all of those fail this match. See
 * `RedactionTest.a dat-suffixed hostname is redacted wherever a real message
 * would put it`, which pins that.
 */
private val GEO_FILE_LIST_MESSAGE = Regex("$GEO_FILE_NAME_REGEX(?:, $GEO_FILE_NAME_REGEX)*")

internal val BASE64_BLOB_PATTERN = Regex("""\b[A-Za-z0-9+/_-]{24,}={0,2}\b""")

/**
 * IPv6 literals, compressed (`::1`, `2001:db8::1`) or full (eight groups).
 *
 * M2 made IPv6 a first-class parse target, so an IPv6 server address is now
 * something that genuinely reaches a diagnostic. Requiring two or more colons is
 * what keeps this off ordinary text: `203.0.113.44:443` has one, and Go's
 * `pkg: message` error chaining never puts two colons adjacent to hex runs.
 *
 * This is only the *candidate* shape. [isIpv6Address] decides, because two
 * colons of pure decimals is also what a clock reads like — see residual §2.
 */
internal val IPV6_PATTERN = Regex("""(?<![0-9A-Fa-f:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:.])""")

/**
 * Three colons is the point past which a decimal-only run stops being a plausible
 * clock. `12:34:56` has two; `1:2:3:4` is not a time anyone writes.
 */
private const val MIN_DECIMAL_ONLY_IPV6_COLONS = 3

/**
 * Whether an [IPV6_PATTERN] candidate is an address rather than a timestamp.
 *
 * Residual §2 found `started at 12:34:56` being destroyed to protect nothing,
 * which is §10.4's failure in the other direction. Three signals separate the
 * two, and any one of them is enough:
 *
 * - a `::` run — no clock elides a field;
 * - a hex letter in any group — `db8`, `fd00`, `8a2e`;
 * - three or more colons — a clock has at most two.
 *
 * A pure-decimal two-colon run is therefore the only thing that passes through,
 * and it is not a well-formed IPv6 address: without `::` an address carries
 * seven colons, and with `::` the first signal already caught it.
 */
internal fun isIpv6Address(candidate: String): Boolean =
    candidate.contains("::") ||
        candidate.any { it in "abcdefABCDEF" } ||
        candidate.count { it == ':' } >= MIN_DECIMAL_ONLY_IPV6_COLONS

/** The minimum colons [IPV6_PATTERN]'s `{2,7}` repetition requires. */
private const val MIN_IPV6_CANDIDATE_COLONS = 2

/** A character [IPV6_PATTERN]'s own core class — `[0-9A-Fa-f]` plus the literal `:` — would accept. */
private fun isIpv6AlphabetChar(c: Char): Boolean = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F') || c == ':'

/**
 * Replaces exactly what `s.replace(IPV6_PATTERN) { if (isIpv6Address(it.value)) SENTINEL else it.value }`
 * would, without [IPV6_PATTERN]'s whole-string lookaround scan — row 7's measured
 * dominant cost (M8.5 spec §3.2, Task 21; `docs/agent/research/2026-09-26-m8.5-row7-release.md`).
 *
 * ## Why this matches [IPV6_PATTERN] on every input
 *
 * Every character [IPV6_PATTERN] can *consume* is a hex digit or `:` — its core,
 * `(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}`, has no other character class in
 * it. So any match is entirely contained in one maximal run of
 * [isIpv6AlphabetChar] characters. Its lookbehind, `(?<![0-9A-Fa-f:.])`, then
 * forbids the match from starting anywhere *inside* such a run: the character
 * immediately before an interior position is itself hex-or-colon, which the
 * lookbehind rejects. The only position where "the character before" is *not*
 * part of the run is the run's own first character (or the start of the whole
 * string). The lookahead, `(?![0-9A-Fa-f:.])`, argues the same from the other
 * end. So a run has **at most one** possible match: the run in its entirety —
 * and only when neither neighbour (if one exists) is `.`, the one forbidden
 * character [isIpv6AlphabetChar] doesn't already exclude from the run itself.
 *
 * That turns "scan the whole message for a match, backtracking over the
 * quantified group at every position" into: find each maximal
 * [isIpv6AlphabetChar] run with one linear scan (no backtracking, because
 * nothing is being matched yet — this is a character-class walk); reject a
 * run outright if either side touches a `.`; and only for what's left, ask
 * whether the run as a *whole* satisfies [IPV6_PATTERN] via `Regex.matches`
 * on that short candidate substring rather than `Regex.replace` on the
 * (potentially much longer) message. [isIpv6Address] then decides exactly as
 * before, on exactly the same candidate string.
 *
 * `Regex.matches` requires the match to consume the *entire* input region
 * with no anchors needed, so it is immune to the `$`-before-a-trailing-line-
 * terminator subtlety a literal `^...$` would carry — not that it would
 * matter here, since [isIpv6AlphabetChar] excludes every line-terminator
 * character from ever appearing inside a candidate run in the first place.
 *
 * ## ICU
 *
 * Nothing here leans on `\s`, `\d`, `\b` or case-insensitive matching — the
 * three divergence sources this file's other gates have to reason about
 * (`hasNonAscii`, `hasColonBeforeWhitespace`). [isIpv6AlphabetChar] is three
 * fixed, case-explicit `Char` ranges plus a literal, identical under the
 * JVM's `java.util.regex` and Android's ICU-backed engine, so there is no
 * JVM/device gap to close for the scan itself. The one regex call this
 * function still makes — `IPV6_PATTERN.matches(run)` — runs the *same*
 * compiled pattern the ungated code always ran, so whatever ICU does with
 * its lookaround is unchanged from before this function existed.
 * `RedactionOracleTest`'s IPv6-dense corpus is the equivalence proof this
 * reasoning predicts; `IcuRedactionProbeTest.icuIpv6CandidateScanMatchesTheWholeRun`
 * is the on-device check for this new code path, per the task's "add
 * on-device cases for any new code path" rule.
 */
internal fun redactIpv6Candidates(s: String): String {
    if (s.none { it == ':' }) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (!isIpv6AlphabetChar(s[i])) {
            out.append(s[i])
            i++
            continue
        }
        val start = i
        while (i < s.length && isIpv6AlphabetChar(s[i])) i++
        val run = s.substring(start, i)
        val precededByDot = start > 0 && s[start - 1] == '.'
        val followedByDot = i < s.length && s[i] == '.'
        val isCandidate =
            !precededByDot &&
                !followedByDot &&
                run.count { it == ':' } >= MIN_IPV6_CANDIDATE_COLONS &&
                IPV6_PATTERN.matches(run)
        out.append(if (isCandidate && isIpv6Address(run)) SENTINEL else run)
    }
    return out.toString()
}

/**
 * A destination as the *value* of a named key: `"address":"vpnserver"` from a
 * JSON echo, `address=vpnserver` from a structured logger.
 *
 * Both are shapes residual §2 observed coming out of the core, and both defeat
 * [LABELLED_HOST_PATTERN] for the same reason: that rule needs whitespace after
 * the label, and here the separator is punctuation.
 *
 * The value group deliberately stops at a quote, comma, brace, bracket or space
 * so `{"address":"vpnserver","port":443}` gives up the host and keeps the port.
 */
internal val KEYED_HOST_PATTERN =
    Regex("""\b(address|server|host|sni|servername|domain)\b"?\s*[:=]\s*"?([^"\s,}\]]+)""", RegexOption.IGNORE_CASE)

/** The value group of [KEYED_HOST_PATTERN]. */
internal const val KEYED_VALUE_GROUP = 2

/**
 * Go's error chaining puts the innermost context first: `vpnserver: connection
 * refused` is what a bare dial failure looks like, and there is no label in
 * front of it to key on. Position is again the only signal — a token at the
 * start of a "word: word" run, followed by a colon and a space.
 *
 * Anchored to a token boundary (`(?<!\S)`, "not preceded by a non-whitespace
 * character") rather than to the literal start of the string. A code review
 * proved by execution that the original `^`-anchor never fires on the one call
 * path that matters: every `XrayException` this codebase throws is built as
 * `"libXray $method failed: ${'$'}{response.optString("error")}"` (see
 * `LibXrayInvoke.call` in `:core:xray`), so the Go text this rule exists for is
 * *never* the whole message in production — it is always preceded by that
 * fixed prefix, which `^` cannot see past. A token boundary sees `vpnserver:`
 * no matter what came before it, at the cost of also matching every other
 * `word:`-followed-by-space run in the string, not only the first.
 *
 * That breadth is why every such token is still filtered by [NON_HOST_WORDS]
 * rather than trusted on position alone: in `dial tcp: lookup vpnserver: no
 * such host`, `tcp:` sits at exactly the same shape as `vpnserver:` and is
 * only left alone because `tcp` is a recognised non-host word. A server
 * genuinely named `tcp` would leak — the same fail-safe trade this file makes
 * everywhere else.
 *
 * Guarded by [NON_HOST_WORDS] because the same position holds a package name in
 * `json: cannot unmarshal ...`. As everywhere here, the guard fails safe: a
 * server genuinely named `json` leaks the string "json".
 */
internal val BARE_HOST_PREFIX_PATTERN = Regex("""(?<!\S)(\S+):(?=\s)""")

/** The candidate group of [BARE_HOST_PREFIX_PATTERN] — the leading token. */
internal const val BARE_TOKEN_GROUP = 1

/**
 * The token introduced by a word that names a destination.
 *
 * Every pattern above is shape-based, which means a single-label host —
 * `vpnserver`, an internal name with no dot in it — matches none of them and
 * passes straight through. There is no shape that distinguishes it from an
 * ordinary word, so position is the only signal available: whatever follows
 * `dial`/`address`/`server`/`host` is a destination.
 *
 * The optional middle group swallows Go's network token, so
 * `dial tcp <host>` redacts the host rather than the word `tcp`.
 *
 * Note what this deliberately does **not** do: guess at short secrets by shape.
 * `shortId 0123abcd` and `password s3cret` still pass through, because a rule
 * that redacts any short token after any suggestive word destroys far more
 * diagnostics than it protects. Closing that is a design change scoped to M3.
 */
internal val LABELLED_HOST_PATTERN =
    Regex("""\b(dial|address|server|host|lookup)\b(\s+(?:tcp|udp|ip)[46]?\b)?(\s+)(\S+)""", RegexOption.IGNORE_CASE)

/**
 * Words that follow a destination label in our own diagnostics and cannot be
 * hosts — "vless address **is** missing", "server **refused** the connection".
 *
 * Without this, [LABELLED_HOST_PATTERN] eats the verb and leaves
 * "vless address <redacted> missing", which is §10.4's diagnostic destroyed to
 * protect a word that was never a secret. It fails in the safe direction: a
 * server genuinely named `is` would leak the string "is", which discloses
 * nothing.
 *
 * The second line is the set [BARE_HOST_PREFIX_PATTERN] needs, plus the network
 * and package tokens Go puts where a host would otherwise sit: `dial tcp:` is
 * two labels deep before the host appears, and `json:` is not a destination at
 * all.
 */
private const val NON_HOST_WORD_LIST =
    "is was are were has have had must not no cannot and or of in to for the a an " +
        "entry entries missing invalid unknown unreachable refused failed name names " +
        "tcp udp ip ip4 ip6 tcp4 tcp6 udp4 udp6 unix dial lookup json yaml xray config " +
        "error warning io net http tls context proto"

private val NON_HOST_WORDS: Set<String> = NON_HOST_WORD_LIST.split(" ").toSet()

/** The trailing `(\S+)` of [LABELLED_HOST_PATTERN] — the candidate destination. */
internal const val LABELLED_TOKEN_GROUP = 4

/**
 * Removes anything that could identify a server or authenticate to it.
 *
 * ARCHITECTURE.md §5.6: server addresses, UUIDs, REALITY keys, and subscription
 * URLs are secrets and must be redacted in every log path, including crash
 * output and the in-app log viewer.
 *
 * Deliberately over-broad. A redacted diagnostic that is harder to read costs a
 * few minutes; a leaked REALITY key costs the user their server, silently.
 *
 * Order matters. URLs are stripped before hostnames and UUIDs so a whole share
 * link collapses to one token rather than a row of them, and IPv4 addresses go
 * before hostnames because `203.0.113.44` also satisfies the hostname shape.
 *
 * The three positional rules — [KEYED_HOST_PATTERN], [BARE_HOST_PREFIX_PATTERN]
 * and [LABELLED_HOST_PATTERN] — run **last**, after every shape-based pattern
 * has had its turn. That ordering is what preserves the useful half of a
 * diagnostic: by the time they look at `dial tcp 203.0.113.44:443 refused`, the
 * address is already a sentinel and the token reads `<sentinel>:443`, so the
 * rule leaves it alone and the port survives. Run first, it would have
 * swallowed the port with the address.
 *
 * Idempotent: every pattern writes the sentinel rather than [REDACTED], and the
 * single swap at the end is what materialises the marker. Nothing downstream in
 * the pass can chew on a marker that does not exist yet — which is also how the
 * label rule recognises an already-redacted token. That matters because
 * `ConnectionStateParcel` redacts on both sides of the IPC boundary.
 *
 * One exemption, and it is anchored to the entire message rather than to a
 * token: a string that is nothing but a geo filename list passes through
 * untouched. See [GEO_FILE_LIST_MESSAGE] for why the anchoring is the whole
 * point — `geoip.dat` is not a secret, but `corp.internal.dat` inside a quoted
 * config is.
 */
public fun redact(message: String): String {
    if (GEO_FILE_LIST_MESSAGE.matches(message)) return message
    return redactEveryPattern(message)
}

private val KEYED_WORDS = listOf("address", "server", "host", "sni", "domain")
private val LABEL_WORDS = listOf("dial", "address", "server", "host", "lookup")

/**
 * True when [s] contains any character outside the ASCII range.
 *
 * Necessary before trusting [KEYED_WORDS]/[LABEL_WORDS] as a skip signal on a
 * real device: Android's `java.util.regex` is ICU-backed, and ICU's
 * case-insensitive matching does *full* Unicode case folding, which is
 * sometimes many-to-one — German `ß` folds to `ss`, and the ligatures `ﬆ`/`ﬅ`
 * fold to `st`. That means [KEYED_HOST_PATTERN]/[LABELLED_HOST_PATTERN], both
 * compiled with `RegexOption.IGNORE_CASE`, can match `addreß`, `ADDREẞ`, `hoﬆ`
 * or `hoﬅ` as the word `address`/`host` on-device, even though Kotlin's
 * `String.contains(ignoreCase = true)` — which folds one character at a time
 * and can never turn one character into two — says the word isn't there. A
 * gate that trusted only the Kotlin keyword check would sometimes skip a
 * pattern ICU would still fire on the real engine: a leak our JVM tests
 * cannot see, because the JVM's `java.util.regex` doesn't fold this way.
 * Any non-ASCII character forces the pattern to run instead. For pure-ASCII
 * input, Kotlin's `ignoreCase` is already a superset of ICU's folding, so the
 * cheap keyword check alone is safe and the gate can still skip.
 */
internal fun hasNonAscii(s: String): Boolean = s.any { it.code >= ASCII_LIMIT }

/** Whether [s] contains any of [words], case-insensitively per Kotlin's (ASCII-safe) folding. */
private fun containsAnyIgnoreCase(
    s: String,
    words: List<String>,
): Boolean = words.any { s.contains(it, ignoreCase = true) }

/** Necessary for [KEYED_HOST_PATTERN]: see [hasNonAscii] for why non-ASCII alone must pass. */
internal fun keyedHostGate(s: String): Boolean = hasNonAscii(s) || containsAnyIgnoreCase(s, KEYED_WORDS)

/** Necessary for [LABELLED_HOST_PATTERN]: see [hasNonAscii] for why non-ASCII alone must pass. */
internal fun labelledHostGate(s: String): Boolean = hasNonAscii(s) || containsAnyIgnoreCase(s, LABEL_WORDS)

/**
 * The same passes in the same order, each behind a **necessary** condition for
 * its pattern to match at all (M8.5 spec §3.2, as amended: row 7 measured ten
 * ungated regex passes at 1.31 ms per line). A gate may only skip a pattern that
 * cannot match; `RedactionOracleTest` holds this function equal to the ungated
 * original. Each gate tests the *current* intermediate string, because each
 * pattern runs on the previous one's output.
 */
internal fun redactEveryPattern(message: String): String {
    var s = message.replace(REDACTED, SENTINEL)
    if (s.contains("://")) s = s.replace(URL_PATTERN, SENTINEL)
    if (s.contains('-')) s = s.replace(UUID_PATTERN, SENTINEL)
    if (s.contains('.')) s = s.replace(IPV4_PATTERN, SENTINEL)
    if (s.count { it == ':' } >= 2) {
        s = redactIpv6Candidates(s)
    }
    if (s.contains('.')) s = s.replace(HOSTNAME_PATTERN, SENTINEL)
    if (hasBase64Run(s)) s = s.replace(BASE64_BLOB_PATTERN, SENTINEL)
    if (keyedHostGate(s)) {
        s = s.replace(KEYED_HOST_PATTERN) { match -> replaceTail(match, KEYED_VALUE_GROUP) }
    }
    if (hasColonBeforeWhitespace(s)) {
        s = s.replace(BARE_HOST_PREFIX_PATTERN) { match -> replaceHead(match, BARE_TOKEN_GROUP) }
    }
    if (labelledHostGate(s)) {
        s = s.replace(LABELLED_HOST_PATTERN) { match -> replaceTail(match, LABELLED_TOKEN_GROUP) }
    }
    return s.replace(SENTINEL, REDACTED)
}

/** [BASE64_BLOB_PATTERN]'s minimum run length, mirrored in [hasBase64Run]'s gate. */
private const val BASE64_RUN_LENGTH = 24

/** The ASCII boundary: [BASE64_BLOB_PATTERN]'s `[A-Za-z0-9]` class is 7-bit only. */
private const val ASCII_LIMIT = 128

/** A character [BASE64_BLOB_PATTERN] itself would accept as part of its run. */
private fun isBase64RunChar(c: Char): Boolean =
    (c.isLetterOrDigit() && c.code < ASCII_LIMIT) || c == '+' || c == '/' || c == '_' || c == '-'

/** Necessary for [BASE64_BLOB_PATTERN]: a run of [BASE64_RUN_LENGTH]+ characters from its class. */
internal fun hasBase64Run(s: String): Boolean {
    var run = 0
    for (c in s) {
        run = if (isBase64RunChar(c)) run + 1 else 0
        if (run >= BASE64_RUN_LENGTH) return true
    }
    return false
}

/** U+0085, NEL: Unicode `White_Space`, which ICU's `(?=\s)` matches, but [Char.isWhitespace] does not. */
private const val NEL = '\u0085'

/**
 * Necessary for [BARE_HOST_PREFIX_PATTERN]: a `:` immediately followed by
 * whitespace. `(?=\s)` is what the pattern actually tests, and on Android
 * `\s` is ICU's `\p{White_Space}`, which recognises [NEL] even though
 * [Char.isWhitespace] does not — checked explicitly here so the reason
 * survives a `git blame` rather than being folded silently into
 * `isWhitespace()`.
 */
internal fun hasColonBeforeWhitespace(s: String): Boolean {
    for (i in 0 until s.length - 1) {
        val next = s[i + 1]
        if (s[i] == ':' && (next.isWhitespace() || next == NEL)) return true
    }
    return false
}

/**
 * Whether a positional candidate should be left alone: already a sentinel, or a
 * word from [NON_HOST_WORDS]. Punctuation is trimmed first so `tcp:` and
 * `entries,` are recognised.
 */
private fun isNotAHost(token: String): Boolean =
    token.contains(SENTINEL) || token.lowercase().trim(',', '.', ':', ';', '"') in NON_HOST_WORDS

/**
 * Replaces the candidate group of a positional match, given that the group is
 * the match's trailing text. Dropping its length leaves everything the rule
 * matched in front of it — label, separator, quote — intact.
 */
internal fun replaceTail(
    match: MatchResult,
    group: Int,
): String {
    val token = match.groupValues[group]
    return if (isNotAHost(token)) match.value else match.value.dropLast(token.length) + SENTINEL
}

/**
 * The mirror of [replaceTail] for a rule whose candidate group is the match's
 * *leading* text, so what follows it — the colon in
 * `vpnserver: connection refused` — survives.
 */
internal fun replaceHead(
    match: MatchResult,
    group: Int,
): String {
    val token = match.groupValues[group]
    return if (isNotAHost(token)) match.value else SENTINEL + match.value.drop(token.length)
}
