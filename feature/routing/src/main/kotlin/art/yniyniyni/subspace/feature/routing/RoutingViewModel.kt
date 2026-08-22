// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.GeoDownloadProgress
import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.model.requiredGeoFiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
            combine(source.subscriptionNames, source.downloadProgress, ::Pair),
        ) { sets, activeId, installed, failed, (names, downloads) ->
            val rows =
                sets.map { stored ->
                    stored.toRow(
                        isActive = stored.ruleSet.id == activeId,
                        // Per row, not one shared answer: a row that owns a
                        // generation reads from it, and the shared catalogue
                        // cannot speak for it. `installed` remains the answer
                        // for rows that read the shared root, and
                        // installedGeoFilesFor returns exactly that for them.
                        installed = if (stored.usesOwnGeneration) source.installedGeoFilesFor(stored) else installed,
                        failed = failed,
                        subscriptionName = stored.subscriptionId?.let(names::get),
                        download = downloads[stored.ruleSet.id],
                    )
                }
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
     * Copies [id]'s rules into a new, editable rule set with no provenance.
     *
     * Spec §4.2's other half: an imported profile is read-only because its
     * provider owns it and the next sync overwrites it, so editing means
     * editing a copy the provider does not own.
     */
    fun duplicate(id: Long) {
        viewModelScope.launch {
            val original = _state.value.ruleSets.firstOrNull { it.id == id } ?: return@launch
            // Through the importer, not upsert: a copy of a profile that owns a
            // generation must own copied files, or its geosite:/geoip: rules
            // silently start resolving against whatever the shared catalogue
            // happens to hold under the same name.
            source.duplicate(id, copyNameFor(original.name, _state.value.ruleSets.map { it.name }.toSet()))
        }
    }

    /**
     * The next routing import awaiting review — a deeplink or a
     * provider-delivered directive.
     *
     * Exposed rather than acted on here: applying it is the import sheet's
     * job, and this ViewModel does not own that sheet.
     */
    val pendingOffer: StateFlow<RoutingImportOffer?> =
        // Eagerly, not WhileSubscribed: an offer delivered between this
        // ViewModel's creation and the screen's first composition would
        // otherwise never be observed, and a deeplink's whole job is to arrive
        // before the screen does.
        source.pendingOffer.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Clears [offer] once the sheet has taken it. */
    fun consumePendingOffer(offer: RoutingImportOffer) {
        source.consumePendingOffer(offer)
    }

    /** Stops the download [id] is running, leaving its previous generation live. */
    fun cancelDownload(id: Long) {
        source.cancelDownload(id)
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

private fun StoredRuleSet.toRow(
    isActive: Boolean,
    installed: Set<String>,
    failed: Set<String>,
    subscriptionName: String?,
    download: GeoDownloadProgress?,
): RuleSetRow {
    val required = ruleSet.requiredGeoFiles()
    return RuleSetRow(
        id = ruleSet.id,
        name = ruleSet.name,
        entryCount = ruleSet.entryCount,
        isActive = isActive,
        missingGeoFiles = required - installed,
        // Narrower than "any geo asset has a lastFailure": only a failure on a
        // file *this* set references is worth showing on *this* row. The
        // consequence is deliberate — a literal-only set never shows the
        // marker, because no failed download can affect it.
        hasFailedGeoUpdate = required.any { it in failed },
        sourceKind = sourceKind,
        subscriptionName = subscriptionName,
        assetState = assetState,
        assetFailure = assetFailure,
        hasUnappliedDns = hasUnappliedDns,
        downloadProgress = download?.let { GeoProgress(it.downloadedBytes, it.totalBytes) },
        requiresGeoFiles = required.isNotEmpty(),
    )
}

/**
 * The name a copy of [original] takes.
 *
 * The name column is uniquely indexed and `upsert` resolves a fresh row **by
 * name**, so a copy keeping the original's name would silently overwrite it
 * rather than duplicate it — which is precisely what "Duplicate and edit" must
 * not do to a profile the user is trying to preserve.
 */
private fun copyNameFor(
    original: String,
    taken: Set<String>,
): String {
    val base = "$original copy"
    if (base !in taken) return base
    return generateSequence(2) { it + 1 }.first { "$base $it" !in taken }.let { "$base $it" }
}
