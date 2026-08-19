// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The per-app picker's state holder.
 *
 * Edits are a **draft** (spec §7.3): [setMode] and [toggle] mutate this class
 * only, and [save] is the single call that writes. On a connected tunnel that
 * write is a reconnect, so committing once at an explicit Save rather than on
 * every toggle is the difference between one drop and one per tick.
 */
@HiltViewModel
internal class PerAppViewModel
@Inject
constructor(
    private val source: PerAppSource,
) : ViewModel() {
    private val _state = MutableStateFlow(PerAppState())
    val state: StateFlow<PerAppState> = _state.asStateFlow()

    /**
     * The **raw** stored selection the draft is compared against to decide
     * [PerAppState.isDirty], and the value [discard] returns to.
     *
     * Raw, from [PerAppSource.userSelection], because this class round-trips it:
     * it seeds the draft from here and writes the draft back with
     * [PerAppSource.apply]. Seeding from the *effective* selection instead would
     * lose every package the moment the stored mode was `Off`, and the next save
     * would write that loss back as fact.
     */
    private var stored: PerAppSelection = PerAppSelection.OFF

    /** Every selected package, installed or not — [PerAppState.rows] may be filtered by a query. */
    private var selected: Set<String> = emptySet()

    private var installedNames: Set<String> = emptySet()

    /**
     * Every row, unfiltered — [PerAppState.rows] is this after [visibleRows].
     *
     * Declared before `init`, not after: property initializers run in declaration
     * order, so a declaration below the init block would re-run `= emptyList()`
     * after it and discard whatever was assigned.
     */
    private var allRows: List<AppRow> = emptyList()

    init {
        viewModelScope.launch {
            stored = source.userSelection.first()
            selected = stored.packages
            val installed = source.installed()
            installedNames = installed.mapTo(mutableSetOf()) { it.packageName }
            val installedRows = installed.map { app -> AppRow(app.packageName, app.label, app.packageName in selected) }
            allRows = selectedFirst(installedRows + ghostRows(selected - installedNames))
            _state.update { it.copy(mode = stored.mode) }
            refresh()
        }
        // So the screen can say a Save will reconnect before the user commits.
        // Not a gate on anything: save() rebuilds unconditionally and lets the
        // service decide whether there is a tunnel to rebuild.
        source.isTunnelActive
            .onEach { active -> _state.update { it.copy(isTunnelActive = active) } }
            .launchIn(viewModelScope)
    }

    /**
     * Selected rows first, each group still sorted by label (spec §7.2). Ghost
     * rows are selected by definition, so they sort in among the selected group
     * by their fallback label rather than trailing the whole list.
     *
     * Applied once, at load, and never again: re-ordering inside [visibleRows]
     * would move a row out from under the finger that had just ticked it, and the
     * next tap would land on whatever slid into its place. The order the user is
     * shown holds for as long as they are on the screen; it is recomputed the
     * next time they open the picker.
     */
    private fun selectedFirst(rows: List<AppRow>): List<AppRow> =
        rows.sortedWith(compareByDescending<AppRow> { it.isSelected }.thenBy { it.label.lowercase() })

    /**
     * Rows for packages that are selected but no longer installed (spec §7.2).
     * The label falls back to the package name because there is no application to
     * ask for one — which is also what tells the user which entry is the ghost.
     */
    private fun ghostRows(missing: Set<String>): List<AppRow> =
        missing.sorted().map { name -> AppRow(name, name, isSelected = true, isInstalled = false) }

    private fun visibleRows(query: String): List<AppRow> =
        allRows
            .map { row -> row.copy(isSelected = row.packageName in selected) }
            .filter { row -> query.isBlank() || row.label.contains(query, ignoreCase = true) }

    private fun refresh() {
        _state.update { current ->
            current.copy(
                rows = visibleRows(current.query),
                // The whole draft, not the visible part of it — see
                // [PerAppState.selectedCount].
                selectedCount = selected.size,
                isDirty = current.mode != stored.mode || selected != stored.packages,
            )
        }
    }

    fun setMode(mode: PerAppMode) {
        _state.update { it.copy(mode = mode) }
        refresh()
    }

    fun toggle(packageName: String) {
        selected = if (packageName in selected) selected - packageName else selected + packageName
        refresh()
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        refresh()
    }

    /** Throws away the draft and returns to what is stored. */
    fun discard() {
        selected = stored.packages
        _state.update { it.copy(mode = stored.mode) }
        refresh()
    }

    /**
     * Writes the draft.
     *
     * Refuses an empty allow-list rather than storing a state the service will
     * decline to start — the same re-check `RoutingViewModel.activate` performs,
     * for the same reason: the screen disabling a control is a hint, this is the
     * gate.
     */
    fun save() {
        if (_state.value.isEmptyAllowList) return
        if (!_state.value.isDirty) return
        val mode = _state.value.mode
        val packages = selected
        viewModelScope.launch {
            source.apply(mode, packages)
            stored = PerAppSelection(mode, packages)
            refresh()
            // One reconnect, at the explicit Save. Guarded on isDirty above so a
            // Save on an unchanged draft never costs the user their connection —
            // and on nothing else. Whether there is anything to rebuild is the
            // service's call, not this one: `reapplyPerApp` gates on
            // `ownTunnelActive()`, which covers every Connecting stage as well as
            // Connected, and is a documented no-op otherwise. Re-deciding it here
            // from a connection state this class only observes would exclude the
            // several seconds of a cold start — during which the start sequence
            // may already have read the old selection, so the tunnel would come
            // up on it while Room and this screen hold the new one, with nothing
            // to reconcile them until the next manual connect.
            source.reapply()
        }
    }
}
