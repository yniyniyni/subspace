// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.requiredGeoFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the rule set list screen: the activation gate (§4.3 of the M5 spec)
 * that decides whether each stored [art.yniyniyni.subspace.core.model.RoutingRuleSet]
 * can be turned on, and the write path for turning one on, off, or deleting
 * it.
 *
 * [RoutingSource]'s three flows are combined rather than collected
 * independently ([art.yniyniyni.subspace.feature.settings.SettingsViewModel]'s
 * shape, which this otherwise follows) because every field on a row —
 * [RuleSetRow.isActive], [RuleSetRow.missingGeoFiles],
 * [RuleSetRow.hasFailedGeoUpdate] — depends on more than one of them at once;
 * three independent `onEach`s would each overwrite the others' contribution
 * to the same row.
 */
@HiltViewModel
internal class RoutingViewModel
@Inject
constructor(
    private val source: RoutingSource,
) : ViewModel() {
    private val _state = MutableStateFlow(RoutingState())
    val state: StateFlow<RoutingState> = _state.asStateFlow()

    init {
        combine(
            source.ruleSets,
            source.activeRuleSetId,
            source.installedGeoFiles,
            source.failedGeoFiles,
        ) { sets, activeId, installed, failed ->
            val rows = sets.map { set -> set.toRow(set.id == activeId, installed, failed) }
            RoutingState(ruleSets = rows, activeRuleSetId = activeId)
        }.onEach { next -> _state.update { next } }
            .launchIn(viewModelScope)
    }

    /**
     * Activates [id], or turns routing off when it is `null`.
     *
     * Re-checks [RuleSetRow.canActivate] before writing — [RoutingListScreen]
     * disabling the row's selector is a hint, not a guarantee, and the gate
     * belongs here, not only in the UI.
     */
    fun activate(id: Long?) {
        viewModelScope.launch {
            if (id == null) {
                source.setActive(null)
                return@launch
            }
            val row = _state.value.ruleSets.firstOrNull { it.id == id }
            if (row?.canActivate == true) {
                source.setActive(id)
            }
        }
    }

    /**
     * Deletes [id]. If it is the active rule set, clears the active id first.
     *
     * `RoutingResolver` already treats a dangling active id as routing off
     * rather than as an error, so leaving one behind would not wedge the
     * tunnel — but it would leave the stored setting disagreeing with what the
     * user sees, and the next rule set to be assigned that row id would
     * silently become active.
     *
     * Compares [id] against [RoutingState.activeRuleSetId] directly (fix round
     * 1, Minor) rather than re-deriving through `ruleSets.firstOrNull { it.id
     * == id }?.isActive` — the two must agree by construction
     * ([toRow] derives `isActive` from this same comparison), so the lookup
     * was strictly more code for the identical answer.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            if (_state.value.activeRuleSetId == id) {
                source.setActive(null)
            }
            source.delete(id)
        }
    }
}

private fun RoutingRuleSet.toRow(
    isActive: Boolean,
    installed: Set<String>,
    failed: Set<String>,
): RuleSetRow {
    val required = requiredGeoFiles()
    return RuleSetRow(
        id = id,
        name = name,
        entryCount = entryCount,
        isActive = isActive,
        missingGeoFiles = required - installed,
        // Narrower than "any geo asset has a lastFailure": only a failure on a
        // file *this* set references is worth showing on *this* row. The
        // consequence is deliberate — a literal-only set never shows the
        // marker, because no failed download can affect it.
        hasFailedGeoUpdate = required.any { it in failed },
    )
}
