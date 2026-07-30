// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

private const val REDACTED = "<redacted>"

// A sentinel no pattern below can match, so an already-redacted string survives
// a second pass unchanged. See the note on idempotence in [redact].
private const val SENTINEL = "R"

private val UUID_PATTERN =
    Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
private val URL_PATTERN = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")
private val IPV4_PATTERN = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
private val HOSTNAME_PATTERN = Regex("""\b(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}\b""")
private val BASE64_BLOB_PATTERN = Regex("""\b[A-Za-z0-9+/_-]{24,}={0,2}\b""")

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
private val IPV6_PATTERN = Regex("""(?<![0-9A-Fa-f:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:.])""")

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
private fun isIpv6Address(candidate: String): Boolean =
    candidate.contains("::") ||
        candidate.any { it in "abcdefABCDEF" } ||
        candidate.count { it == ':' } >= MIN_DECIMAL_ONLY_IPV6_COLONS

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
private val KEYED_HOST_PATTERN =
    Regex("""\b(address|server|host|sni|servername|domain)\b"?\s*[:=]\s*"?([^"\s,}\]]+)""", RegexOption.IGNORE_CASE)

/** The value group of [KEYED_HOST_PATTERN]. */
private const val KEYED_VALUE_GROUP = 2

/**
 * Go's error chaining puts the innermost context first: `vpnserver: connection
 * refused` is what a bare dial failure looks like, and there is no label in
 * front of it to key on. Position is again the only signal — first token of the
 * message, followed by a colon and a space.
 *
 * Anchored, so it sees one token per message and cannot walk into
 * `dial tcp: lookup <host>: no such host`, where the colon belongs to Go's
 * network word rather than to a host.
 *
 * Guarded by [NON_HOST_WORDS] because the same position holds a package name in
 * `json: cannot unmarshal ...`. As everywhere here, the guard fails safe: a
 * server genuinely named `json` leaks the string "json".
 */
private val BARE_HOST_PREFIX_PATTERN = Regex("""^(\S+):(?=\s)""")

/** The candidate group of [BARE_HOST_PREFIX_PATTERN] — the leading token. */
private const val BARE_TOKEN_GROUP = 1

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
private val LABELLED_HOST_PATTERN =
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
private const val LABELLED_TOKEN_GROUP = 4

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
 */
public fun redact(message: String): String =
    message
        .replace(REDACTED, SENTINEL)
        .replace(URL_PATTERN, SENTINEL)
        .replace(UUID_PATTERN, SENTINEL)
        .replace(IPV4_PATTERN, SENTINEL)
        .replace(IPV6_PATTERN) { match -> if (isIpv6Address(match.value)) SENTINEL else match.value }
        .replace(HOSTNAME_PATTERN, SENTINEL)
        .replace(BASE64_BLOB_PATTERN, SENTINEL)
        .replace(KEYED_HOST_PATTERN) { match -> replaceTail(match, KEYED_VALUE_GROUP) }
        .replace(BARE_HOST_PREFIX_PATTERN) { match -> replaceHead(match, BARE_TOKEN_GROUP) }
        .replace(LABELLED_HOST_PATTERN) { match -> replaceTail(match, LABELLED_TOKEN_GROUP) }
        .replace(SENTINEL, REDACTED)

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
private fun replaceTail(
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
private fun replaceHead(
    match: MatchResult,
    group: Int,
): String {
    val token = match.groupValues[group]
    return if (isNotAHost(token)) match.value else SENTINEL + match.value.drop(token.length)
}
