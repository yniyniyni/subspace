// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.sync

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
 *   name takes `<escaped-name>#<ordinal>`, zero-based, in response order.
 *
 * The "every occurrence, not just the later ones" clause is the load-bearing
 * part. Keying the first duplicate by bare name and the rest by ordinal means a
 * provider inserting a server above them shifts every ordinal, and the whole
 * group churns on a refresh that changed one entry.
 *
 * Names are compared trimmed, so `"Tokyo"` and `" Tokyo "` are one name — a
 * provider's template emitting stray whitespace must not silently double a
 * server. Comparison is otherwise case-sensitive by design: `"Tokyo"` and
 * `"tokyo"` are distinct names. That is the minimal reading of the spec — only
 * trimming is called for — recorded here so a later refactor does not fold in
 * case-insensitive comparison and silently change refresh identity for a
 * provider whose casing varies between fetches.
 *
 * **Injectivity.** `<name>#<ordinal>` alone is not injective: a literal name
 * that happens to look like another row's generated key (e.g. a provider
 * naming one server `"Tokyo#0"` while two other rows are named `"Tokyo"`) can
 * collide with it, corrupting the unique `(groupId, subscriptionKey)` index
 * this feeds. To close that, the name is escaped before use: every `\`
 * becomes `\\` and every `#` becomes `\#`. That escaping guarantees a `#`
 * character can appear inside an encoded name **only** as the second
 * character of an escaped pair — always immediately preceded by the backslash
 * that produced it, and an encoded name's own token boundaries are always
 * self-contained (each original character contributes either one plain
 * character or one complete backslash-pair, never a dangling half of one), so
 * appending anything after an encoded name can never retroactively turn its
 * trailing character into part of a new escape pair. The ordinal separator is
 * then appended as a bare, un-escaped `#`, which by construction cannot occur
 * inside *any* encoded name. So: a bare (unique-name) key is always exactly
 * an encoded name with no trailing separator, and an ordinal key always ends
 * in an un-escaped `#<digits>` — two shapes that can never produce the same
 * string, so no bare key can collide with an ordinal key. Escaping is also
 * what keeps the encoding itself injective (distinct names cannot escape to
 * the same string — the escape/backslash pairing is uniquely decodable left
 * to right), so two bare keys can't collide with each other, and two ordinal
 * keys can only collide if both their encoded name *and* their row index
 * match — impossible, since every row's index is unique.
 *
 * Known limitation (documented, not solved here): two servers in one
 * subscription with distinct names but byte-identical outbounds still collide
 * on `(groupId, identityHash)` downstream. `subscriptionKey` disambiguates by
 * name, not by outbound content, so that collision is out of scope for this
 * function and is tracked as a bounded limitation for a later milestone
 * (spec §4.3).
 */
internal fun subscriptionKeysFor(names: List<String>): List<String> {
    val trimmed = names.map(String::trim)
    val counts = trimmed.filter(String::isNotEmpty).groupingBy { it }.eachCount()

    return trimmed.mapIndexed { index, name ->
        val escaped = name.escapeKeySeparator()
        if (name.isNotEmpty() && counts[name] == 1) escaped else "$escaped#$index"
    }
}

/**
 * Escapes `\` and `#` so the ordinal separator appended by [subscriptionKeysFor]
 * can never be confused with a `#` that was part of the original name. See the
 * injectivity note on [subscriptionKeysFor] for why this makes the derivation
 * collision-free.
 */
private fun String.escapeKeySeparator(): String = replace("\\", "\\\\").replace("#", "\\#")
