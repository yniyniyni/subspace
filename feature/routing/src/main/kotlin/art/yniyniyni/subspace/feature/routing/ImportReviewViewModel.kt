// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.GeoFilePreview
import art.yniyniyni.subspace.core.data.ImportOutcome
import art.yniyniyni.subspace.core.data.ImportPreview
import art.yniyniyni.subspace.core.data.RoutingProfileImporter
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.parser.routing.ImportResult
import art.yniyniyni.subspace.core.parser.routing.RoutingProfileImport
import art.yniyniyni.subspace.core.parser.routing.RoutingVerb
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [RoutingProfileImporter] slice this sheet needs.
 *
 * [RoutingProfileImporter]'s constructor is `internal` to `:core:data`, so this
 * module cannot build a real instance to test against. [BoundImportReviewSource]
 * is the one place that touches the importer; [ImportReviewViewModel] goes
 * through this interface so [ImportReviewViewModelTest] can exercise preview
 * and apply against a plain JVM fake. Same shape as [RoutingSource].
 */
internal interface ImportReviewSource {
    suspend fun preview(profile: RoutingProfile): ImportPreview

    suspend fun apply(
        profile: RoutingProfile,
        verb: RoutingVerb,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): ImportOutcome

    suspend fun disableRouting()
}

@Singleton
internal class BoundImportReviewSource
@Inject
constructor(
    private val importer: RoutingProfileImporter,
) : ImportReviewSource {
    override suspend fun preview(profile: RoutingProfile): ImportPreview = importer.preview(profile)

    override suspend fun apply(
        profile: RoutingProfile,
        verb: RoutingVerb,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): ImportOutcome = importer.apply(profile, verb, sourceKind, subscriptionId)

    override suspend fun disableRouting() = importer.disableRouting()
}

/**
 * Backs the import review sheet: parse, then [ImportReviewSource.preview],
 * then wait. [ImportReviewSource.apply] runs only from [confirm].
 *
 * `/onadd`'s activation is this class's job — [ImportPreview.willActivate]
 * describes `/add` only (no rule set currently active). Unchanged fingerprints
 * never raise the sheet (spec §7.3).
 */
@HiltViewModel
internal class ImportReviewViewModel
@Inject
constructor(
    private val source: ImportReviewSource,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportReviewState())
    val state: StateFlow<ImportReviewState> = _state.asStateFlow()

    private var pending: PendingImport? = null

    /**
     * Offers [text] for review as an import arriving over [sourceKind].
     *
     * [sourceKind] is a parameter rather than a constant because this sheet is
     * the single funnel for all five channels of spec §5.2, and the channel is
     * what a row's provenance badge and its read-only status are derived from.
     * Recording every import as [RoutingSourceKind.Deeplink] would make the
     * badge lie about where a profile came from — and the badge is the only
     * place the user ever learns that their provider, not they, installed it.
     *
     * [subscriptionId] is set only by the provider channels (`Header`, `Body`),
     * which own the row: deleting the subscription deletes its profiles.
     */
    fun offer(
        text: String,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long? = null,
    ) {
        viewModelScope.launch {
            when (val result = RoutingProfileImport.parse(text)) {
                is ImportResult.Imported -> onImported(result, sourceKind, subscriptionId)
                ImportResult.DisableRouting -> onDisable()
                is ImportResult.Invalid -> {
                    pending = null
                    _state.value =
                        ImportReviewState(
                            stage = Stage.Rejected,
                            problem = result.problem,
                        )
                }
            }
        }
    }

    fun confirm() {
        val action = pending ?: return
        if (_state.value.stage != Stage.Reviewing) return
        viewModelScope.launch {
            _state.update { it.copy(stage = Stage.Applying) }
            applyPending(action)
        }
    }

    /**
     * [ImportReviewSource.apply] / [ImportReviewSource.disableRouting] can still throw
     * (the importer rethrows unrelated `SQLiteConstraintException`). Without this
     * catch, [Stage.Applying] sticks, Cancel is disabled, and [dismiss] is a no-op.
     *
     * [CancellationException] is rethrown unmodified — a cancelled
     * `viewModelScope` means this [ViewModel] is being torn down, same as
     * [RuleSetEditorViewModel.saveDraft].
     */
    // TooGenericExceptionCaught: apply/disable fail in ways this ViewModel cannot enumerate (§10.4).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun applyPending(action: PendingImport) {
        try {
            when (action) {
                PendingImport.Disable -> source.disableRouting()
                is PendingImport.Profile ->
                    source.apply(
                        action.profile,
                        action.verb,
                        action.sourceKind,
                        action.subscriptionId,
                    )
            }
            pending = null
            _state.update { it.copy(stage = Stage.Done) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { it.copy(stage = Stage.Reviewing) }
        }
    }

    fun dismiss() {
        if (_state.value.stage == Stage.Applying) return
        pending = null
        _state.value = ImportReviewState(stage = Stage.Done)
    }

    private suspend fun onImported(
        result: ImportResult.Imported,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ) {
        val preview = source.preview(result.profile)
        if (preview.decision == RoutingRepository.UpdateDecision.Unchanged) {
            pending = null
            _state.value = ImportReviewState(stage = Stage.Done)
            return
        }
        pending = PendingImport.Profile(result.profile, result.verb, sourceKind, subscriptionId)
        _state.value =
            ImportReviewState(
                stage = Stage.Reviewing,
                name = result.profile.name,
                replacesExisting = preview.replacesExisting,
                bucketCounts = bucketCounts(result.profile),
                defaultRouteIsDirect = result.profile.globalProxy == false,
                geoDownloads = preview.geoFiles.map { it.toDownloadPreview() },
                hasUnappliedDns = result.profile.hasUnappliedDns,
                willActivate = result.verb == RoutingVerb.OnAdd || preview.willActivate,
            )
    }

    private fun onDisable() {
        pending = PendingImport.Disable
        _state.value = ImportReviewState(stage = Stage.Reviewing, isDisableRouting = true)
    }
}

private sealed interface PendingImport {
    data class Profile(
        val profile: RoutingProfile,
        val verb: RoutingVerb,
        val sourceKind: RoutingSourceKind,
        val subscriptionId: Long?,
    ) : PendingImport

    data object Disable : PendingImport
}

private fun bucketCounts(profile: RoutingProfile): Map<RouteOutcome, Int> =
    RouteOutcome.entries
        .associateWith { outcome ->
            val bucket = profile.bucket(outcome)
            bucket.sites.size + bucket.ips.size
        }.filterValues { it > 0 }

private fun GeoFilePreview.toDownloadPreview(): GeoDownloadPreview =
    GeoDownloadPreview(
        host = hostOf(url),
        fileName = fileName,
        approximateBytes = approximateBytes,
        alreadyOnDevice = alreadyOnDevice,
    )

/** Host only; a failed parse yields an empty host rather than echoing the URL. */
private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()
