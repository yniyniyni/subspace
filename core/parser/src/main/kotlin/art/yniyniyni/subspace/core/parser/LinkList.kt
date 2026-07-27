// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile

/** Parses newline-separated share links while preserving independent failures. */
internal fun parseLinkList(text: String): ParseOutcome {
    val profiles = mutableListOf<Profile>()
    val failures = mutableListOf<ParseFailure>()
    var entryIndex = 0

    for (line in text.lineSequence()) {
        val entry = line.trim()
        if (entry.isEmpty()) {
            continue
        }

        when (val result = parseShareLink(entry, entryIndex)) {
            is LinkResult.Ok -> profiles += result.profile
            is LinkResult.Bad -> failures += result.failure
        }
        entryIndex++
    }

    return if (profiles.isEmpty() && failures.isEmpty()) {
        ParseOutcome.EMPTY
    } else {
        ParseOutcome(profiles, failures)
    }
}
