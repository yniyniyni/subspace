// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What [AddServerSheet] renders.
 *
 * ARCHITECTURE.md §7: a [ParseOutcome][art.yniyniyni.subspace.core.parser.ParseOutcome]
 * always has two halves, and 197 imported with 3 [failures] is a normal,
 * successful outcome — not an error state. [completed] distinguishes "nothing
 * attempted yet" from "attempted and imported nothing, entirely failures" (both
 * have `imported == 0`).
 */
internal data class ImportState(
    val busy: Boolean = false,
    val completed: Boolean = false,
    val imported: Int = 0,
    val failures: List<ParseFailure> = emptyList(),
) {
    val total: Int get() = imported + failures.size
}

@HiltViewModel
internal class ImportViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /**
     * Parses [raw] and persists whatever [SubscriptionParser] could make of it.
     *
     * §5.6/§10.4: never throws and never logs [raw] — a clipboard paste or an
     * imported file *is* config content. [ImportState.failures] carries only
     * [ParseFailure]'s closed vocabulary, never the input that produced it.
     */
    fun import(raw: String) {
        viewModelScope.launch {
            _state.value = ImportState(busy = true)

            // §5.3: the whole pass — a SHA-256 plus regex validation per
            // entry, a YAML document parse per Clash entry — must not run on
            // viewModelScope's Main.immediate dispatcher. Default, not IO:
            // this is pure CPU work with no blocking call in it, and IO's
            // pool is sized for threads parked on syscalls (the M1
            // predecessor of this file, `HomeViewModel.parseInput`, made the
            // same call for the same reason before Task 17 retired it).
            val outcome = withContext(Dispatchers.Default) { SubscriptionParser.parse(raw) }

            // §6: provenance decides storage, not the profiles that came out
            // of it. Raw Xray JSON is stored byte-for-byte — untrimmed, exact
            // whitespace and key order included, since that is what
            // identityHashOfRaw hashes. Detected the same way
            // SubscriptionParser's own top-level dispatch does (a trimmed
            // leading '{'), because nothing in ParseOutcome says which
            // container the input was.
            val rawJson = raw.takeIf { raw.trim().startsWith("{") }

            if (outcome.profiles.isNotEmpty()) {
                val groupId = profileSource.defaultGroupId()
                profileSource.import(outcome.profiles, groupId, rawJson)
            }

            _state.value =
                ImportState(
                    busy = false,
                    completed = true,
                    imported = outcome.profiles.size,
                    failures = outcome.failures,
                )
        }
    }
}
