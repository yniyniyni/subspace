// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.routing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING

private val CONTENT_HORIZONTAL_PADDING = 24.dp
private val CARD_PADDING = 16.dp
private val CARD_GAP = 12.dp
private val ROW_ICON_GAP = 8.dp
private val MARKER_TOP_PADDING = 8.dp
private val MARKER_ICON_SIZE = 16.dp
private val EMPTY_STATE_TOP_PADDING = 48.dp

/**
 * The rule set list screen: the activation gate (M5 spec §4.3) and nowhere
 * else. Reached from Settings, not the bottom nav — see [RoutingListScreen]'s
 * call site in `SubspaceNavHost` for why.
 *
 * Every row shows two independent markers, never conflated (see
 * [RuleSetRow]'s own KDoc): a red "needs geo files" marker blocks the radio
 * selector, a muted "last update failed" marker never does. A disabled radio
 * is only a hint — [RoutingViewModel.activate] re-checks
 * [RuleSetRow.canActivate] itself before writing anything.
 *
 * @param onCreateRuleSet the "+" action, forwarded verbatim to
 *   `SubspaceNavHost`, which navigates to `RuleSetEditor(NEW_RULE_SET)` — the
 *   same reason [art.yniyniyni.subspace.feature.profiles.list.ServersScreen]
 *   takes its own navigation callbacks rather than owning a `NavController`.
 * @param onEditRuleSet a row's edit icon, forwarded to `RuleSetEditor(id)`
 *   for that real row.
 */
@Composable
fun RoutingListScreen(
    onCreateRuleSet: () -> Unit,
    onEditRuleSet: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RoutingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    RoutingListScreenContent(
        state = state,
        actions =
        RoutingListActions(
            onActivate = viewModel::activate,
            onDelete = viewModel::delete,
            onCreateRuleSet = onCreateRuleSet,
            onEditRuleSet = onEditRuleSet,
        ),
        modifier = modifier,
    )
}

/** This screen's callbacks, grouped for the same reason [art.yniyniyni.subspace.feature.home.HomeActions] is. */
internal data class RoutingListActions(
    val onActivate: (Long?) -> Unit,
    val onDelete: (Long) -> Unit,
    val onCreateRuleSet: () -> Unit,
    val onEditRuleSet: (Long) -> Unit,
)

/**
 * The stateless half — see
 * [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s KDoc for why this
 * split exists.
 */
@Composable
internal fun RoutingListScreenContent(
    state: RoutingState,
    actions: RoutingListActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CONTENT_HORIZONTAL_PADDING)
            // Same reason SettingsScreenContent reserves this itself — see
            // FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc.
            .padding(top = CONTENT_HORIZONTAL_PADDING, bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.routing_title), style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = actions.onCreateRuleSet) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.routing_create_description),
                )
            }
        }

        OffRow(
            isOff = state.ruleSets.none { it.isActive },
            onSelect = { actions.onActivate(null) },
            modifier = Modifier.padding(top = CARD_GAP),
        )

        if (state.ruleSets.isEmpty()) {
            EmptyState(modifier = Modifier.padding(top = EMPTY_STATE_TOP_PADDING))
        } else {
            state.ruleSets.forEach { row ->
                RuleSetCard(
                    row = row,
                    onSelect = { actions.onActivate(row.id) },
                    onEdit = { actions.onEditRuleSet(row.id) },
                    onDelete = { actions.onDelete(row.id) },
                    modifier = Modifier.padding(top = CARD_GAP),
                )
            }
        }
    }
}

/**
 * The explicit "routing is off" choice — its own row rather than a
 * `null`-backed entry in the rule set list, because it has no name, no entry
 * count and no geo requirement to render.
 */
@Composable
private fun OffRow(
    isOff: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.routing_off_description)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clickable(onClick = onSelect).semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(CARD_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isOff, onClick = onSelect)
            Spacer(Modifier.width(ROW_ICON_GAP))
            Text(text = stringResource(R.string.routing_off_label), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RuleSetCard(
    row: RuleSetRow,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(CARD_PADDING)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val activateDescription = stringResource(R.string.routing_activate_description, row.name)
                RadioButton(
                    selected = row.isActive,
                    // A disabled control is a hint, not the gate — RoutingViewModel.activate
                    // re-checks canActivate itself, so this only saves the user a tap that
                    // would be silently refused.
                    enabled = row.canActivate,
                    onClick = onSelect,
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
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.routing_edit_description, row.name),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.routing_delete_description, row.name),
                    )
                }
            }

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
        }
    }
}

/**
 * One activation-gate marker line. [text] alone is what a screen reader
 * announces — [Icons.Default.Warning] here is decorative
 * (`contentDescription = null`), the same reasoning [SettingRow][art.yniyniyni.subspace.core.ui.component.SettingRow]'s
 * own icon documents.
 */
@Composable
private fun MarkerRow(
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

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.routing_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.routing_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
