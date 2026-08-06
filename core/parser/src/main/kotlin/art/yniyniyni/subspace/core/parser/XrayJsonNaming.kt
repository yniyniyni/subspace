// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile

/**
 * Device-fixes finding, defect 2: the real subscription's first array element
 * carried 8 `vless` outbounds under one `remarks`, so all 8 profiles from
 * that one document — and therefore all 8 rows in the Servers list —
 * [XrayJson.kt][parseXrayJsonDocument] named identically. `remarks`
 * describes the *document*; it was never meant to distinguish the several
 * profiles one document can yield, and it still should not be abandoned as
 * the primary name over it (`parseVlessDestination`'s `name` precedence is
 * unchanged — see its sibling fix, `root remarks names the profile instead
 * of the outbound tag`, in [XrayJsonTest][art.yniyniyni.subspace.core.parser.XrayJsonTest]).
 *
 * This appends a 1-based ordinal — " (1)", " (2)", … — in outbound order,
 * and only to names that actually collide within one document; a document
 * that yields a single profile keeps the name it computed exactly as
 * before, so this is called from [parseXrayJsonDocument]'s return, once per
 * document/array-element, never across elements.
 *
 * The outbound's own `tag` was considered as the disambiguator instead of a
 * synthetic ordinal, and rejected: the same finding that made `tag` a bad
 * *primary* name — exporters set it to `"proxy"` uniformly — means it
 * collides exactly where `remarks` already collided, so it would
 * disambiguate nothing in the real-world case this fixes. An ordinal is
 * always available and always unique.
 *
 * Split into its own file (not folded into `XrayJson.kt`) because that file
 * was already at detekt's `TooManyFunctions` file threshold — the same
 * reason [ClashYamlNode.kt] and [ClashTransport.kt] are split out.
 */
internal fun disambiguateDuplicateNames(profiles: List<Profile>): List<Profile> {
    val occurrences = profiles.groupingBy { it.name }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return profiles.map { profile ->
        if (occurrences.getValue(profile.name) <= 1) {
            profile
        } else {
            val ordinal = seen.getOrDefault(profile.name, 0) + 1
            seen[profile.name] = ordinal
            profile.copy(name = "${profile.name} ($ordinal)")
        }
    }
}
