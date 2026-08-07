// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.sync

/**
 * The provider's own identity for each server in a response, in order.
 *
 * **Refresh keys on this, never on `identityHash`** — spec D8, written in at the
 * M3 spec's explicit instruction. Because `identityHash` covers the whole
 * outbound, a provider changing a server's SNI produces a *new* row rather than
 * an update: the old row is orphaned along with its `lastConnectedAt`, and the
 * user's "Last used" ordering quietly resets on every rotation.
 *
 * The rule:
 * - A name that is non-empty and occurs **exactly once** is the key.
 * - Otherwise — absent, blank, or duplicated — **every** server sharing that
 *   name takes `<name>#<ordinal>`, zero-based, in response order.
 *
 * The "every occurrence, not just the later ones" clause is the load-bearing
 * part. Keying the first duplicate by bare name and the rest by ordinal means a
 * provider inserting a server above them shifts every ordinal, and the whole
 * group churns on a refresh that changed one entry.
 *
 * Names are compared trimmed, so `"Tokyo"` and `" Tokyo "` are one name — a
 * provider's template emitting stray whitespace must not silently double a
 * server.
 *
 * Known limitation (documented, not solved here): two servers in one
 * subscription with distinct names but byte-identical outbounds still collide
 * on `(groupId, identityHash)` downstream. `subscriptionKey` disambiguates by
 * name, not by outbound content, so that collision is out of scope for this
 * function and is tracked as a bounded limitation for a later milestone.
 */
internal fun subscriptionKeysFor(names: List<String>): List<String> {
    val trimmed = names.map(String::trim)
    val counts = trimmed.filter(String::isNotEmpty).groupingBy { it }.eachCount()

    return trimmed.mapIndexed { index, name ->
        if (name.isNotEmpty() && counts[name] == 1) name else "$name#$index"
    }
}
