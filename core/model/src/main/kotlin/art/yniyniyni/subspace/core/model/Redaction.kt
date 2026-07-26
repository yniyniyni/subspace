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
 * Idempotent: output contains only [REDACTED] markers, parked behind a sentinel
 * during the pass so [BASE64_BLOB_PATTERN] cannot chew on them. That matters
 * because `ConnectionStateParcel` redacts on both sides of the IPC boundary.
 */
public fun redact(message: String): String =
    message
        .replace(REDACTED, SENTINEL)
        .replace(URL_PATTERN, REDACTED)
        .replace(UUID_PATTERN, REDACTED)
        .replace(IPV4_PATTERN, REDACTED)
        .replace(HOSTNAME_PATTERN, REDACTED)
        .replace(BASE64_BLOB_PATTERN, REDACTED)
        .replace(SENTINEL, REDACTED)
