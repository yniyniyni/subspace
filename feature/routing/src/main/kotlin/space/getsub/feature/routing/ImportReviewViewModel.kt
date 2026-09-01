// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.getsub.core.data.GeoFilePreview
import space.getsub.core.data.ImportOutcome
import space.getsub.core.data.ImportPreview
import space.getsub.core.data.RoutingProfileImporter
import space.getsub.core.data.RoutingRepository
import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingProfile
import space.getsub.core.model.RoutingSourceKind
import space.getsub.core.parser.routing.ConversionDrop
import space.getsub.core.parser.routing.ImportResult
import space.getsub.core.parser.routing.RoutingConversion
import space.getsub.core.parser.routing.RoutingProfileImport
import space.getsub.core.parser.routing.RoutingVerb
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

    /** The running apply, so [cancelApply] can stop a download already in flight. */
    private var applyJob: Job? = null

    /** Serialises [offer] so a later offer cannot publish state before an earlier one finishes. */
    private val offerMutex = Mutex()

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
    /**
     * Offers [text] without waiting for an acknowledgement.
     *
     * For the channels the user starts here — clipboard and QR. Nothing is
     * persisted for them, so there is no record to reconcile and nothing to
     * strand if the offer is dropped; the user simply acts again. Runs in
     * [viewModelScope] so popping the scanner does not cancel parse and preview
     * mid-flight.
     *
     * The provider channel must use [offer] instead, and wait for its answer.
     */
    fun offerWithoutAcknowledgement(
        text: String,
        sourceKind: RoutingSourceKind,
    ) {
        viewModelScope.launch { offer(text, sourceKind) }
    }

    /**
     * Suspends until this offer has been resolved into sheet state, and reports
     * whether it was.
     *
     * **The return value is an acknowledgement, and callers must wait for it.**
     * A provider directive stays in the database, so the only thing stopping it
     * being offered again is the caller recording that it was shown. When this
     * was a fire-and-forget `launch`, that record was written while parse and
     * preview were still in flight — so a ViewModel destroyed mid-preview, a
     * preview that threw, or a second offer overtaking the first left the
     * directive marked shown and unreachable for the life of the process.
     *
     * Returns false when nothing was installed as reviewable state, which means
     * the caller must **not** treat the offer as delivered.
     *
     * Serialised: two offers arriving together resolve in order, so the second
     * cannot publish its state and then be overwritten by the first's.
     */
    // TooGenericExceptionCaught: preview reaches Room and the filesystem, which
    // fail in ways this ViewModel cannot enumerate (§10.4).
    @Suppress("TooGenericExceptionCaught")
    suspend fun offer(
        text: String,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long? = null,
    ): Boolean =
        offerMutex.withLock {
            try {
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
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Preview failed. Leaving the offer unacknowledged is what lets
                // it be retried rather than silently swallowed.
                false
            }
        }

    fun confirm() {
        val action = pending ?: return
        if (_state.value.stage != Stage.Reviewing) return
        applyJob =
            viewModelScope.launch {
                _state.update { it.copy(stage = Stage.Applying) }
                applyPending(action)
            }
    }

    /**
     * Stops an import that is already downloading.
     *
     * The only other cancel lives on the routing row, which sits behind this
     * sheet's scrim while [Stage.Applying] — so once a confirmed import started
     * downloading there was no reachable way to stop it.
     *
     * Cancels the apply coroutine rather than the registry entry, so it works
     * before the row id exists. `RoutingProfileImporter` treats the resulting
     * cancellation as a cancelled generation: the previous one stays live and
     * its files stay on disk (spec §7.4). State is restored here because the
     * cancelled coroutine cannot restore it itself.
     */
    fun cancelApply() {
        if (_state.value.stage != Stage.Applying) return
        applyJob?.cancel()
        applyJob = null
        _state.update { it.copy(stage = Stage.Reviewing) }
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
            val outcome =
                when (action) {
                    PendingImport.Disable -> {
                        source.disableRouting()
                        null
                    }
                    is PendingImport.Profile ->
                        source.apply(
                            action.profile,
                            action.verb,
                            action.sourceKind,
                            action.subscriptionId,
                        )
                }
            // A download timeout, a rejected file and a failed install are
            // returned, not thrown. Treating every normal return as success
            // closed the sheet exactly as it does on success and left the user
            // with no reason and no retry.
            if (outcome is ImportOutcome.Failed) {
                _state.update { it.copy(stage = Stage.Failed, failure = outcome.failure) }
                return
            }
            pending = null
            _state.update { it.copy(stage = Stage.Done) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _state.update { it.copy(stage = Stage.Reviewing) }
        }
    }

    /** Returns a failed import to its reviewable state so confirm can be pressed again. */
    fun retry() {
        if (_state.value.stage != Stage.Failed) return
        _state.update { it.copy(stage = Stage.Reviewing, failure = null) }
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
            reviewState(
                profile = result.profile,
                preview = preview,
                willActivate = result.verb == RoutingVerb.OnAdd || preview.willActivate,
                drops = emptyMap(),
            )
    }

    /**
     * Starts review for a config's `routing` converted into a rule set (Task 13),
     * rather than one arriving over a link/QR/clipboard/subscription channel.
     *
     * Runs the same [ImportReviewSource.preview] path [onImported] does — same
     * fingerprint gate, same geo-download disclosure — so this task changes
     * nothing about apply. The only thing this adds is [conversion]'s [drops]
     * reaching [ImportReviewState], which is the whole reason Task 13 counted
     * them: a drop nobody sees is a drop that might as well not be reported.
     *
     * [RoutingSourceKind.Conversion] names this provenance precisely — the user
     * did not paste this rule set's contents, the app derived them from a
     * config's own `routing` block, and the badge is the one place the user
     * learns where a rule set came from (§9). [RoutingSourceKind.Clipboard]
     * would say something that did not happen.
     */
    suspend fun startConversionReview(conversion: RoutingConversion) {
        val profile = conversion.profile
        val preview = source.preview(profile)
        if (preview.decision == RoutingRepository.UpdateDecision.Unchanged) {
            pending = null
            _state.value = ImportReviewState(stage = Stage.Done)
            return
        }
        pending = PendingImport.Profile(profile, RoutingVerb.Add, RoutingSourceKind.Conversion, subscriptionId = null)
        _state.value =
            reviewState(
                profile = profile,
                preview = preview,
                willActivate = preview.willActivate,
                drops = conversion.drops,
            )
    }

    private fun onDisable() {
        pending = PendingImport.Disable
        _state.value = ImportReviewState(stage = Stage.Reviewing, isDisableRouting = true)
    }
}

/** Shared by [ImportReviewViewModel.onImported] and [ImportReviewViewModel.startConversionReview]. */
private fun reviewState(
    profile: RoutingProfile,
    preview: ImportPreview,
    willActivate: Boolean,
    drops: Map<ConversionDrop, Int>,
): ImportReviewState =
    ImportReviewState(
        stage = Stage.Reviewing,
        name = profile.name,
        replacesExisting = preview.replacesExisting,
        replacesActive = preview.replacesActive,
        bucketCounts = bucketCounts(profile),
        defaultRouteIsDirect = profile.globalProxy == false,
        geoDownloads = preview.geoFiles.map { it.toDownloadPreview() },
        dns = profile.dns,
        dnsState = dnsStateOf(profile.dns, sniffingEnabled = true),
        willActivate = willActivate,
        profile = profile,
        drops = drops,
    )

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
