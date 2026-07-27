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
 */
private val IPV6_PATTERN = Regex("""(?<![0-9A-Fa-f:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:.])""")

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
    Regex("""\b(dial|address|server|host)\b(\s+(?:tcp|udp|ip)[46]?\b)?(\s+)(\S+)""", RegexOption.IGNORE_CASE)

/**
 * Words that follow a destination label in our own diagnostics and cannot be
 * hosts — "vless address **is** missing", "server **refused** the connection".
 *
 * Without this, [LABELLED_HOST_PATTERN] eats the verb and leaves
 * "vless address <redacted> missing", which is §10.4's diagnostic destroyed to
 * protect a word that was never a secret. It fails in the safe direction: a
 * server genuinely named `is` would leak the string "is", which discloses
 * nothing.
 */
private const val NON_HOST_WORD_LIST =
    "is was are were has have had must not no cannot and or of in to for the a an " +
        "entry entries missing invalid unknown unreachable refused failed name names"

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
 * [LABELLED_HOST_PATTERN] runs **last**, after every shape-based pattern has
 * had its turn. That ordering is what preserves the useful half of a
 * diagnostic: by the time it looks at `dial tcp 203.0.113.44:443 refused`, the
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
        .replace(IPV6_PATTERN, SENTINEL)
        .replace(HOSTNAME_PATTERN, SENTINEL)
        .replace(BASE64_BLOB_PATTERN, SENTINEL)
        .replace(LABELLED_HOST_PATTERN) { match ->
            val token = match.groupValues[LABELLED_TOKEN_GROUP]
            val alreadyRedacted = token.contains(SENTINEL)
            val notAHost = token.lowercase().trim(',', '.', ':', ';') in NON_HOST_WORDS
            // The token is the final group, so dropping its length leaves the
            // label, Go's optional network word, and the whitespace between.
            if (alreadyRedacted || notAHost) match.value else match.value.dropLast(token.length) + SENTINEL
        }.replace(SENTINEL, REDACTED)
