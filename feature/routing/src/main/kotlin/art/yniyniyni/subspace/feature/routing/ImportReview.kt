// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern.
// TooManyFunctions: one sheet, rendered as small single-purpose composables.
// Splitting the file would separate the state from the bodies that read it.
@file:Suppress("MagicNumber", "TooManyFunctions")

package art.yniyniyni.subspace.feature.routing

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.parser.routing.ImportProblem
import art.yniyniyni.subspace.core.ui.component.SubspaceBottomSheet

private val CONTENT_HORIZONTAL_PADDING = 16.dp
private val CONTENT_BOTTOM_PADDING = 16.dp
private val SECTION_GAP = 12.dp
private val GEO_MARKER_GAP = 4.dp

/** Where the import confirmation currently sits. */
internal sealed interface Stage {
    data object Reviewing : Stage

    data object Applying : Stage

    data object Done : Stage

    data object Rejected : Stage

    /**
     * The import was confirmed and did not land.
     *
     * Distinct from [Rejected], which is a link this app would not parse.
     * This one parsed, the user approved it, and the *installation* failed —
     * so it carries a closed-vocabulary reason and a retry, where Rejected
     * carries a parse problem and nothing to retry.
     */
    data object Failed : Stage
}

/**
 * One geo file the sheet discloses before any byte is fetched.
 *
 * The host is the threat-model disclosure (§A.1 / spec §6); the full URL never
 * lives here, so it cannot leak through a log of this type.
 */
internal data class GeoDownloadPreview(
    val host: String,
    val fileName: String,
    val approximateBytes: Long?,
    val alreadyOnDevice: Boolean,
)

/**
 * What the import confirmation sheet shows.
 *
 * @param geoDownloads hosts only — never a URL (§5.6).
 */
internal data class ImportReviewState(
    val stage: Stage = Stage.Done,
    /**
     * Whether confirming turns routing **off** rather than importing a profile.
     *
     * Stated, not inferred. Deriving it from an empty [name] happens to work
     * today only because a nameless profile is rejected during parse — a
     * coincidence one upstream change away from silently rendering a real
     * profile's rules as the "routing will be turned off" body.
     */
    val isDisableRouting: Boolean = false,
    val name: String = "",
    val replacesExisting: Boolean = false,
    val bucketCounts: Map<RouteOutcome, Int> = emptyMap(),
    val defaultRouteIsDirect: Boolean = false,
    val geoDownloads: List<GeoDownloadPreview> = emptyList(),
    val hasUnappliedDns: Boolean = false,
    val willActivate: Boolean = false,
    val problem: ImportProblem? = null,
    /** Why a confirmed import did not land. Non-null only with [Stage.Failed]. */
    val failure: RuleSetAssetFailure? = null,
)

/**
 * Spec §6's confirmation for every routing-profile import channel.
 *
 * Confirm is deliberately not the default-focused action: the user is
 * consenting to a Dangerous directive, and a sheet they can accept by
 * accident is a sheet that does not confirm.
 */
@Composable
internal fun ImportReviewSheet(
    state: ImportReviewState,
    actions: ImportReviewActions,
    modifier: Modifier = Modifier,
) {
    SubspaceBottomSheet(
        open = state.stage != Stage.Done,
        titleRes = state.titleRes(),
        onDismiss = actions.onDismiss,
        modifier = modifier,
        dismissible = state.stage != Stage.Applying,
    ) {
        ImportReviewSheetContent(state = state, actions = actions)
    }
}

/** The sheet's callbacks, grouped for the same reason [RoutingListActions] is. */
internal data class ImportReviewActions(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
    /** Stops a download already in flight — the only reachable cancel while Applying. */
    val onCancelApply: () -> Unit = {},
    /** Returns a failed import to Reviewing so it can be confirmed again. */
    val onRetry: () -> Unit = {},
)

/**
 * The stateless sheet body — same split [RoutingListScreenContent] documents:
 * [ImportReviewSheet] owns the modal, and instrumented tests drive this
 * function with a plain [ImportReviewState] so they do not need a Hilt
 * ViewModel or a dialog window.
 */
@Composable
internal fun ImportReviewSheetContent(
    state: ImportReviewState,
    actions: ImportReviewActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = CONTENT_HORIZONTAL_PADDING)
            .padding(bottom = CONTENT_BOTTOM_PADDING),
        verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
    ) {
        when {
            state.stage == Stage.Rejected -> RejectedBody(state.problem)
            state.stage == Stage.Failed -> FailedBody(state.name, state.failure)
            state.isDisableRouting -> {
                Text(
                    text = stringResource(R.string.import_review_disable_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> ProfileBody(state)
        }
        if (state.stage == Stage.Applying) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        ActionRow(state = state, actions = actions)
    }
}

@Composable
private fun ProfileBody(state: ImportReviewState) {
    Text(text = state.name, style = MaterialTheme.typography.titleMedium)
    if (state.replacesExisting) {
        Text(
            text = stringResource(R.string.import_review_replaces_existing),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    RouteOutcome.entries.forEach { outcome ->
        val count = state.bucketCounts[outcome] ?: return@forEach
        if (count <= 0) return@forEach
        Text(
            text =
            stringResource(
                R.string.import_review_bucket_count,
                outcome.label(),
                count,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        text =
        stringResource(
            if (state.defaultRouteIsDirect) {
                R.string.import_review_default_direct
            } else {
                R.string.import_review_default_proxy
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    state.geoDownloads.forEach { download ->
        GeoRow(download)
    }
    if (state.hasUnappliedDns) {
        Text(
            text = stringResource(R.string.import_review_dns_unapplied),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Text(
        text =
        stringResource(
            if (state.willActivate) {
                R.string.import_review_will_activate
            } else {
                R.string.import_review_will_not_activate
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun GeoRow(download: GeoDownloadPreview) {
    val context = LocalContext.current
    val size =
        download.approximateBytes?.let { bytes ->
            Formatter.formatShortFileSize(context, bytes)
        }
    val headline =
        if (size == null) {
            stringResource(R.string.import_review_geo_row, download.host, download.fileName)
        } else {
            stringResource(R.string.import_review_geo_row_with_size, download.host, download.fileName, size)
        }
    Column(verticalArrangement = Arrangement.spacedBy(GEO_MARKER_GAP)) {
        Text(text = headline, style = MaterialTheme.typography.bodyMedium)
        if (download.alreadyOnDevice) {
            Text(
                text = stringResource(R.string.import_review_geo_already_on_device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A confirmed import that did not land, with the reason and a retry.
 *
 * §5.6: [RuleSetAssetFailure] is a closed vocabulary and no member carries a
 * URL, so naming the reason discloses nothing about where the file came from.
 */
@Composable
private fun FailedBody(
    name: String,
    failure: RuleSetAssetFailure?,
) {
    Text(text = name, style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(failure.sheetMessageRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * A [RuleSetAssetFailure] always accompanies [Stage.Failed] — the importer
 * returns the pair — so null can only be a future state this `when` has not
 * learned. It gets the least specific reason rather than a claim the state
 * cannot support.
 *
 * Separate from [RuleSetCard]'s mapping of the same enum on purpose: the row
 * reports a set's standing condition ("Update failed — timed out") while the
 * sheet explains what just happened to an import the user confirmed a moment
 * ago. Same vocabulary, different voice.
 */
private fun RuleSetAssetFailure?.sheetMessageRes(): Int =
    when (this) {
        RuleSetAssetFailure.DownloadFailed, null -> R.string.import_review_failed_download
        RuleSetAssetFailure.TimedOut -> R.string.import_review_failed_timeout
        RuleSetAssetFailure.Rejected -> R.string.import_review_failed_rejected
        RuleSetAssetFailure.InstallFailed -> R.string.import_review_failed_install
        RuleSetAssetFailure.Cancelled -> R.string.import_review_failed_cancelled
    }

@Composable
private fun RejectedBody(problem: ImportProblem?) {
    Text(
        text = stringResource(problem.messageRes()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ActionRow(
    state: ImportReviewState,
    actions: ImportReviewActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // While Applying, this sheet blocks swipe, scrim and system-back, and
        // the row's own cancel sits behind it — so stopping the download has to
        // be reachable from here or it is not reachable at all.
        if (state.stage == Stage.Applying) {
            Button(onClick = actions.onCancelApply) {
                Text(stringResource(R.string.import_review_cancel_download))
            }
            return@Row
        }
        // Filled Cancel is composed first so it, not Import, is the default
        // focused action — confirming a Dangerous directive must not be Enter.
        Button(onClick = actions.onDismiss) {
            Text(stringResource(R.string.import_review_dismiss))
        }
        when (state.stage) {
            Stage.Rejected -> Unit
            Stage.Failed ->
                TextButton(onClick = actions.onRetry) {
                    Text(stringResource(R.string.import_review_retry))
                }
            else ->
                TextButton(onClick = actions.onConfirm, enabled = state.stage == Stage.Reviewing) {
                    Text(
                        stringResource(
                            if (state.isDisableRouting) {
                                R.string.import_review_disable_confirm
                            } else {
                                R.string.import_review_confirm
                            },
                        ),
                    )
                }
        }
    }
}

@Composable
private fun RouteOutcome.label(): String =
    when (this) {
        RouteOutcome.BLOCK -> stringResource(R.string.rule_set_editor_outcome_block)
        RouteOutcome.PROXY -> stringResource(R.string.rule_set_editor_outcome_proxy)
        RouteOutcome.DIRECT -> stringResource(R.string.rule_set_editor_outcome_direct)
    }

private fun ImportReviewState.titleRes(): Int =
    when {
        stage == Stage.Rejected -> R.string.import_review_rejected_title
        isDisableRouting -> R.string.import_review_disable_title
        else -> R.string.import_review_title
    }

private fun ImportProblem?.messageRes(): Int =
    when (this) {
        ImportProblem.NotARoutingLink -> R.string.import_review_problem_not_a_routing_link
        ImportProblem.UnknownVerb -> R.string.import_review_problem_unknown_verb
        ImportProblem.MalformedBase64 -> R.string.import_review_problem_malformed_base64
        ImportProblem.MalformedJson -> R.string.import_review_problem_malformed_json
        ImportProblem.MissingName -> R.string.import_review_problem_missing_name
        ImportProblem.NameTooLong -> R.string.import_review_problem_name_too_long
        ImportProblem.InvalidRouteOrder -> R.string.import_review_problem_invalid_route_order
        ImportProblem.InvalidEntry -> R.string.import_review_problem_invalid_entry
        ImportProblem.MalformedGeoUrl -> R.string.import_review_problem_malformed_geo_url
        ImportProblem.InsecureGeoUrl -> R.string.import_review_problem_insecure_geo_url
        ImportProblem.TooLarge -> R.string.import_review_problem_too_large
        // Unreachable: Stage.Rejected is only ever set alongside a problem.
        // It still gets its own string rather than borrowing a specific one —
        // naming a cause the parser never reported would be a guess presented
        // to the user as a finding.
        null -> R.string.import_review_problem_unknown
    }
