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
import kotlinx.coroutines.flow.update
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
 *
 * [imported] and [parsed] are deliberately two different numbers (§10.1 fix,
 * element-provenance report): [parsed] is what [SubscriptionParser] produced;
 * [imported] is what [ProfileSource.import] actually persisted, which can be
 * lower — a raw Xray document whose elements collide only in fields the typed
 * projection cannot see (the §6 `xhttp` residual) stores fewer rows than it
 * parsed profiles. "Imported 15 of 15" must mean 15 rows landed, not 15
 * profiles parsed and only 1 actually stored — that was the bug ([total] and
 * the summary string both read off [parsed], not off what reached storage).
 *

 * [input] is the paste field's own text, held here rather than in
 * `rememberSaveable` (fix round 1, finding 3): the M1 predecessor this
 * file's own KDoc already cites, `HomeViewModel.parseInput`, kept it in
 * ViewModel state for the same reason before Task 17 retired it —
 * `rememberSaveable` marshals its value into the hosting Activity's
 * saved-instance-state `Bundle`, and a pasted config can carry server
 * addresses, UUIDs and REALITY material (§5.6). Plain (non-`SavedStateHandle`)
 * ViewModel state lives in process memory only and is gone on process death,
 * which is the right lifetime for this value — there is nothing here worth
 * surviving a process restart, and every byte of it is exactly the kind of
 * material §5.6 says must not sit somewhere it doesn't need to. [import]
 * clears [input] once an import actually lands a profile (see its own KDoc);
 * a failed attempt keeps the text so the user can see and fix what they
 * pasted.
 *
 * [fileReadFailed] is fix round 1, finding 2's surface for "the picked file
 * could not be read" — a stale/revoked URI, a provider I/O error, or a
 * permission revoked mid-flow. It carries nothing beyond the boolean itself:
 * an I/O exception's message can carry a path or provider detail, so it is
 * never read, let alone stored (§5.6).
 */
internal data class ImportState(
    val input: String = "",
    val busy: Boolean = false,
    val completed: Boolean = false,
    val imported: Int = 0,
    val parsed: Int = 0,
    val failures: List<ParseFailure> = emptyList(),
    val fileReadFailed: Boolean = false,
) {
    /** What was attempted, for the "of N" half of the summary — always [parsed], never [imported]. */
    val total: Int get() = parsed + failures.size
}

@HiltViewModel
internal class ImportViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** The paste field's `onValueChange` — see [ImportState.input]'s own KDoc. */
    fun onInputChanged(text: String) {
        _state.update { it.copy(input = text) }
    }

    /**
     * [AddServerSheet]'s "Import from file" flow calls this before reading
     * the picked document, so the busy indicator covers the file I/O too,
     * not only the parse — and so a previous attempt's stale result/failure
     * banner is cleared before the new one starts, the same reset [import]
     * does for the paste path.
     */
    fun beginFileRead() {
        _state.update { ImportState(input = it.input, busy = true) }
    }

    /**
     * Fix round 1, finding 2: the file picker could not produce readable
     * text — `openInputStream` returned `null` (a stale/revoked URI, which
     * some content providers do instead of throwing) or reading it threw.
     * Either way this is a *reported* outcome, never a silent one (§7/§10.4).
     * Takes no cause — see [ImportState.fileReadFailed]'s KDoc for why.
     */
    fun reportFileReadFailure() {
        _state.update { it.copy(busy = false, fileReadFailed = true) }
    }

    /**
     * Parses [raw] and persists whatever [SubscriptionParser] could make of it.
     *
     * §5.6/§10.4: never throws and never logs [raw] — a clipboard paste or an
     * imported file *is* config content. [ImportState.failures] carries only
     * [ParseFailure]'s closed vocabulary, never the input that produced it.
     */
    fun import(raw: String) {
        viewModelScope.launch {
            _state.update { ImportState(input = it.input, busy = true) }

            // §5.3: the whole pass — a SHA-256 plus regex validation per
            // entry, a YAML document parse per Clash entry — must not run on
            // viewModelScope's Main.immediate dispatcher. Default, not IO:
            // this is pure CPU work with no blocking call in it, and IO's
            // pool is sized for threads parked on syscalls (the M1
            // predecessor of this file, `HomeViewModel.parseInput`, made the
            // same call for the same reason before Task 17 retired it).
            val outcome = withContext(Dispatchers.Default) { SubscriptionParser.parse(raw) }

            // Element-provenance fix: `:core:parser` now attaches each profile's own
            // provenance (Profile.rawJson) as it parses, so this call no longer needs to
            // re-derive "was this raw JSON" from raw's own leading character — see
            // ProfileRepository.import's KDoc for what ProfileSource.import does with it,
            // and what the returned count means.
            val storedCount =
                if (outcome.profiles.isNotEmpty()) {
                    val groupId = profileSource.defaultGroupId()
                    profileSource.import(outcome.profiles, groupId)
                } else {
                    0
                }

            _state.update { current ->
                ImportState(
                    // Fix round 1, finding 3: clear the pasted secret material
                    // once it has actually landed a profile — a fully-failed
                    // attempt keeps it so the user can see and fix what they
                    // pasted, rather than losing it on every attempt.
                    input = if (outcome.profiles.isNotEmpty()) "" else current.input,
                    busy = false,
                    completed = true,
                    imported = storedCount,
                    parsed = outcome.profiles.size,
                    failures = outcome.failures,
                )
            }
        }
    }
}
