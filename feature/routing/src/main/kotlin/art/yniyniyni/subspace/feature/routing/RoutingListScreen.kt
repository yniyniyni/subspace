// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.routing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import kotlinx.coroutines.launch

private val CONTENT_HORIZONTAL_PADDING = 24.dp
private val CARD_PADDING = 16.dp
private val CARD_GAP = 12.dp
private val ROW_ICON_GAP = 8.dp
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
 * @param onBack pops back to Settings. This is a pushed destination and the
 *   floating nav pill is hidden on it, so without this the only way out is the
 *   system gesture — the same reason `SubscriptionDetailScreen` carries one.
 */
@Composable
fun RoutingListScreen(
    onCreateRuleSet: () -> Unit,
    onEditRuleSet: (Long) -> Unit,
    onScanQr: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RoutingViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Resolved on this destination's own entry, which is the same entry
    // RoutingQrScanRoute is handed — so a scanned payload raises *this* sheet.
    val importViewModel: ImportReviewViewModel = hiltViewModel()
    val importState by importViewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    RoutingListScreenContent(
        state = state,
        actions =
        RoutingListActions(
            onActivate = viewModel::activate,
            onDelete = viewModel::delete,
            onCreateRuleSet = onCreateRuleSet,
            onEditRuleSet = onEditRuleSet,
            onBack = onBack,
            onDuplicate = viewModel::duplicate,
            onCancelDownload = viewModel::cancelDownload,
            onImportFromClipboard = {
                scope.launch {
                    // Deliberately unvalidated: a clipboard holding something
                    // that is not a routing link produces the sheet's Rejected
                    // stage naming the problem, which tells the user more than
                    // a menu item that silently does nothing.
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    importViewModel.offerWithoutAcknowledgement(text, RoutingSourceKind.Clipboard)
                }
            },
            onScanQr = onScanQr,
        ),
        modifier = modifier,
    )

    // A deeplink navigated here, or a sync left a provider directive waiting.
    // Either way the sheet is what the user actually confirms — rule 1 has no
    // per-channel exemption, and the provider channels are the ones §A.1's
    // threat model is written about. Consumed by value so an offer that
    // arrived while this one was being handed over is not discarded unread.
    val pendingOffer by viewModel.pendingOffer.collectAsStateWithLifecycle()
    LaunchedEffect(pendingOffer) {
        pendingOffer?.let { offer ->
            val accepted =
                when (offer) {
                    is RoutingImportOffer.Deeplink ->
                        importViewModel.offer(offer.text, RoutingSourceKind.Deeplink)
                    // Header, not Body: the transport a provider actually uses
                    // is the response header, and M4 established Remnawave
                    // emits no body directives at all. A body line reaching
                    // here is defensive, and calling it Header would only
                    // mislabel a channel nothing exercises.
                    is RoutingImportOffer.Provider ->
                        importViewModel.offer(offer.text, RoutingSourceKind.Header, offer.subscriptionId)
                }
            // Only once the sheet actually owns it. Acknowledging an offer the
            // sheet never took would strand a persisted provider directive: it
            // stays in the database and would be filtered out forever.
            if (accepted) viewModel.consumePendingOffer(offer)
        }
    }

    ImportReviewSheet(
        state = importState,
        actions =
        ImportReviewActions(
            onConfirm = importViewModel::confirm,
            onDismiss = importViewModel::dismiss,
            onCancelApply = importViewModel::cancelApply,
            onRetry = importViewModel::retry,
        ),
    )
}

/** This screen's callbacks, grouped for the same reason [art.yniyniyni.subspace.feature.home.HomeActions] is. */
internal data class RoutingListActions(
    val onActivate: (Long?) -> Unit,
    val onDelete: (Long) -> Unit,
    val onCreateRuleSet: () -> Unit,
    val onEditRuleSet: (Long) -> Unit,
    val onBack: () -> Unit,
    /** Copies a read-only profile into an editable set, then opens it (spec §4.2). */
    val onDuplicate: (Long) -> Unit = {},
    /** Stops an in-flight generation, leaving the previous one live. */
    val onCancelDownload: (Long) -> Unit = {},
    /** Reads the primary clip and hands it to the review sheet, unvalidated. */
    val onImportFromClipboard: () -> Unit = {},
    /** Opens the shared scanner; its result goes to the same sheet. */
    val onScanQr: () -> Unit = {},
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
            // No FLOATING_NAV_CONTENT_BOTTOM_PADDING: that constant is for the
            // three top-level screens that scroll beneath the floating nav
            // pill. This is a pushed destination and the pill is hidden on it,
            // so reserving its height would just be dead space under the last
            // card.
            .padding(vertical = CONTENT_HORIZONTAL_PADDING),
    ) {
        ScreenHeader(actions = actions)

        if (state.canTurnRoutingOff) {
            OffRow(
                isOff = state.activeRuleSetId == null,
                onSelect = { actions.onActivate(null) },
                modifier = Modifier.padding(top = CARD_GAP),
            )
        }

        if (state.ruleSets.isEmpty()) {
            EmptyState(modifier = Modifier.padding(top = EMPTY_STATE_TOP_PADDING))
        } else {
            state.ruleSets.forEach { row ->
                RuleSetCard(
                    row = row,
                    actions =
                    RuleSetCardActions(
                        onSelect = { actions.onActivate(row.id) },
                        onEdit = { actions.onEditRuleSet(row.id) },
                        onDuplicate = { actions.onDuplicate(row.id) },
                        onDelete = { actions.onDelete(row.id) },
                        onCancelDownload = { actions.onCancelDownload(row.id) },
                    ),
                    modifier = Modifier.padding(top = CARD_GAP),
                )
            }
        }
    }
}

/**
 * The two user-initiated import channels (spec §5.2).
 *
 * Neither pre-validates what it reads. A clipboard holding something that is not
 * a routing link produces the review sheet's `Rejected` stage naming the
 * problem, which tells the user more than a disabled menu item that never says
 * why.
 */
@Composable
private fun ImportMenu(
    onImportFromClipboard: () -> Unit,
    onScanQr: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.routing_import_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.routing_import_clipboard)) },
                onClick = {
                    expanded = false
                    onImportFromClipboard()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.routing_import_qr)) },
                onClick = {
                    expanded = false
                    onScanQr()
                },
            )
        }
    }
}

/** Back, title, and the two ways a rule set enters this screen. */
@Composable
private fun ScreenHeader(actions: RoutingListActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.routing_back_description),
                )
            }
            Text(text = stringResource(R.string.routing_title), style = MaterialTheme.typography.headlineMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImportMenu(
                onImportFromClipboard = actions.onImportFromClipboard,
                onScanQr = actions.onScanQr,
            )
            IconButton(onClick = actions.onCreateRuleSet) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.routing_create_description),
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
