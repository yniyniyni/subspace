// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the padding/size tokens this file reads —
// all are declared in RoutingListScreen.kt alongside the rest of the scale.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.routing

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState

/**
 * One rule set's card, split out of [RoutingListScreen] once M6 gave each row a
 * provenance badge, its own generation state and a cancellable download — the
 * same extraction [RoutingListScreen]'s `routingDestinations` counterpart in
 * `:app` documents, and for the same reason: a file that renders one row well
 * is easier to reason about than one that renders a screen and a row.
 */

/** One card's callbacks, grouped for the same reason [RoutingListActions] is. */
/**
 * This file's own copy of the spacing scale. Every screen in this module
 * declares its own `private` copy of the tokens it reads — `PerAppScreen` and
 * `RuleSetEditorScreen` do the same — so a shared `internal` set would collide
 * with all of them rather than unify them.
 */
private val CARD_PADDING = 16.dp
private val ROW_ICON_GAP = 8.dp
private val MARKER_TOP_PADDING = 8.dp
private val MARKER_ICON_SIZE = 16.dp

internal data class RuleSetCardActions(
    val onSelect: () -> Unit,
    val onEdit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onCancelDownload: () -> Unit,
)

@Composable
internal fun RuleSetCard(
    row: RuleSetRow,
    actions: RuleSetCardActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(CARD_PADDING)) {
            RuleSetCardHeader(row = row, actions = actions)

            if (row.missingGeoFiles.isNotEmpty()) {
                val missing = row.missingGeoFiles.sorted().joinToString()
                MarkerRow(
                    text = stringResource(R.string.routing_missing_geo_files, missing),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (row.hasFailedGeoUpdate) {
                MarkerRow(
                    text = stringResource(R.string.routing_update_failed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Three independent axes, never collapsed into one: missingGeoFiles
            // above blocks activation, hasFailedGeoUpdate reports a refresh of
            // an installed file, and this reports *this set's own* generation.
            AssetStateRow(row = row, onCancelDownload = actions.onCancelDownload)
            if (row.hasUnappliedDns) {
                MarkerRow(
                    text = stringResource(R.string.routing_dns_not_applied),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The card's selector, labels and per-row actions. */
@Composable
private fun RuleSetCardHeader(
    row: RuleSetRow,
    actions: RuleSetCardActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        val activateDescription = stringResource(R.string.routing_activate_description, row.name)
        RadioButton(
            selected = row.isActive,
            // A disabled control is a hint, not the gate — RoutingViewModel.activate
            // re-checks canActivate itself, so this only saves the user a tap that
            // would be silently refused.
            enabled = row.canActivate,
            onClick = actions.onSelect,
            modifier = Modifier.semantics { contentDescription = activateDescription },
        )
        Spacer(Modifier.width(ROW_ICON_GAP))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = pluralStringResource(R.plurals.routing_entry_count, row.entryCount, row.entryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = row.provenanceLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // A read-only row gets Duplicate in place of Edit, not beside it: the
        // next sync overwrites this set, so an edit made here would silently
        // disappear (spec §4.2).
        if (row.isReadOnly) {
            // A word rather than an icon: the project depends only on
            // material-icons-core, which has no copy glyph, and adding
            // material-icons-extended for one row would need a §10.7
            // justification this does not have. "Duplicate" also reads more
            // plainly than any glyph would for an unusual action.
            val duplicateDescription = stringResource(R.string.routing_duplicate_description, row.name)
            TextButton(
                onClick = actions.onDuplicate,
                modifier = Modifier.semantics { contentDescription = duplicateDescription },
            ) {
                Text(stringResource(R.string.routing_duplicate_label))
            }
        } else {
            IconButton(onClick = actions.onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.routing_edit_description, row.name),
                )
            }
        }
        IconButton(onClick = actions.onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.routing_delete_description, row.name),
            )
        }
    }
}

/** The provenance badge — spec §9's first row addition. */
@Composable
private fun RuleSetRow.provenanceLabel(): String =
    when (sourceKind) {
        null -> stringResource(R.string.routing_source_made_here)
        RoutingSourceKind.Deeplink -> stringResource(R.string.routing_source_deeplink)
        RoutingSourceKind.Qr -> stringResource(R.string.routing_source_qr)
        RoutingSourceKind.Clipboard -> stringResource(R.string.routing_source_clipboard)
        RoutingSourceKind.Header,
        RoutingSourceKind.Body,
        ->
            subscriptionName?.let { stringResource(R.string.routing_source_subscription, it) }
                ?: stringResource(R.string.routing_source_provider)
    }

/**
 * This set's own generation state, and the cancel that stops it.
 *
 * A live [RuleSetRow.downloadProgress] wins over [RuleSetRow.assetState]: the
 * row is `Pending` for the whole materialisation, but only part of it is
 * actually moving bytes, and a byte count is the more specific truth while it
 * lasts.
 */
@Composable
private fun AssetStateRow(
    row: RuleSetRow,
    onCancelDownload: () -> Unit,
) {
    val progress = row.downloadProgress
    if (progress != null) {
        val context = LocalContext.current
        val downloaded = Formatter.formatShortFileSize(context, progress.downloadedBytes)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                    progress.totalBytes?.let { total ->
                        stringResource(
                            R.string.routing_downloading,
                            downloaded,
                            Formatter.formatShortFileSize(context, total),
                        )
                    } ?: stringResource(R.string.routing_downloading_unknown_total, downloaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // An undeclared total cannot drive a determinate bar; showing
                // one anyway would invent a fraction out of nothing.
                if (progress.totalBytes != null && progress.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            IconButton(onClick = onCancelDownload) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.routing_download_cancel_description, row.name),
                )
            }
        }
        return
    }
    // A literal-only set has no geo files, so none of these lines apply to it.
    // It reported "Geo files ready" about files it does not have.
    if (!row.requiresGeoFiles && row.assetState != RuleSetAssetState.Failed) return
    when (row.assetState) {
        RuleSetAssetState.None -> Unit
        // Plain text, no warning glyph: these are ordinary states, and
        // MarkerRow's icon made a working profile look like it had a problem.
        RuleSetAssetState.Ready -> AssetStateNote(R.string.routing_asset_ready)
        RuleSetAssetState.Pending -> AssetStateNote(R.string.routing_asset_pending)
        RuleSetAssetState.Failed ->
            MarkerRow(
                text = stringResource(row.assetFailure.messageRes()),
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/** One non-alarming asset-state line. [MarkerRow] is for the states that warrant a warning. */
@Composable
private fun AssetStateNote(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
    )
}

/**
 * A [RuleSetAssetState.Failed] row always carries a failure — the repository
 * writes the pair in one statement — so null here can only be a future state
 * this `when` has not learned yet. It gets the least specific of the reasons
 * rather than a claim the row cannot support.
 */
private fun RuleSetAssetFailure?.messageRes(): Int =
    when (this) {
        RuleSetAssetFailure.DownloadFailed, null -> R.string.routing_asset_failed_download
        RuleSetAssetFailure.TimedOut -> R.string.routing_asset_failed_timeout
        RuleSetAssetFailure.Rejected -> R.string.routing_asset_failed_rejected
        RuleSetAssetFailure.Unsupplied -> R.string.routing_asset_failed_unsupplied
        RuleSetAssetFailure.InstallFailed -> R.string.routing_asset_failed_install
        RuleSetAssetFailure.Cancelled -> R.string.routing_asset_failed_cancelled
    }

/**
 * One activation-gate marker line. [text] alone is what a screen reader
 * announces — [Icons.Default.Warning] here is decorative
 * (`contentDescription = null`), the same reasoning [SettingRow][art.yniyniyni.subspace.core.ui.component.SettingRow]'s
 * own icon documents.
 */
@Composable
internal fun MarkerRow(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(MARKER_ICON_SIZE),
        )
        Spacer(Modifier.width(ROW_ICON_GAP / 2))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
