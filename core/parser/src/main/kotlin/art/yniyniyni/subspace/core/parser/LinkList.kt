// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Profile

/** Parses newline-separated share links while preserving independent failures. */
internal fun parseLinkList(text: String): ParseOutcome {
    val profiles = mutableListOf<Profile>()
    val failures = mutableListOf<ParseFailure>()

    text
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEachIndexed { index, entry ->
            when (val result = parseShareLink(entry, index)) {
                is LinkResult.Ok -> profiles += result.profile
                is LinkResult.Bad -> failures += result.failure
            }
        }

    return if (profiles.isEmpty() && failures.isEmpty()) {
        ParseOutcome.EMPTY
    } else {
        ParseOutcome(profiles, failures)
    }
}
