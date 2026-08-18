// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern. TooManyFunctions: the mode selector, the two independent
// warnings, search, the row list and the discard dialog are each their own
// section — the same reasoning RuleSetEditorScreen.kt's own suppression
// gives for its six entry-list sections.
@file:Suppress("MagicNumber", "TooManyFunctions")

package art.yniyniyni.subspace.feature.routing

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.model.PerAppMode

private val CONTENT_HORIZONTAL_PADDING = 24.dp
private val SECTION_GAP = 20.dp
private val ROW_GAP = 12.dp
private val ROW_ICON_GAP = 8.dp
private val ROW_VERTICAL_PADDING = 12.dp
private val APP_ICON_SIZE = 40.dp
private val MARKER_ICON_SIZE = 16.dp
private val MARKER_TOP_PADDING = 8.dp

/**
 * The per-app proxy picker (M5.5 spec §7): mode selector, search and the
 * installed-app list. Reached from Settings, like [RoutingListScreen] — a
 * pushed destination with the floating nav pill hidden, so it carries its own
 * [onBack].
 *
 * Edits are a **draft** ([PerAppViewModel]'s own KDoc) — [onBack] is a real
 * navigation pop, never called while [PerAppState.isDirty] without asking
 * first. Both ways to leave converge on that one gate: the system back
 * gesture via [BackHandler] below, and [PerAppScreenContent]'s own back
 * button via the `onBack` this builds into [PerAppActions] — see the
 * `showDiscardDialog` flag's own comment below for why the confirmation
 * itself lives here rather than split across both.
 *
 * @param onBack pops back to Settings, exactly like [RoutingListScreen]'s own.
 */
@Composable
fun PerAppScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PerAppViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Owned here, not inside PerAppScreenContent: a back gesture (BackHandler,
    // below) and the on-screen back button both need to raise the *same*
    // confirmation, and PerAppScreenContent's fixed (state, actions, modifier)
    // signature has no channel to report a dialog back up. One flag, one
    // dialog, both paths route through the onBack built into actions below —
    // spec §7.3: a back gesture must never silently commit or drop an edit.
    var showDiscardDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = state.isDirty) { showDiscardDialog = true }

    PerAppScreenContent(
        state = state,
        actions =
        PerAppActions(
            onSetMode = viewModel::setMode,
            onToggle = viewModel::toggle,
            onSearch = viewModel::search,
            onSave = viewModel::save,
            onDiscard = viewModel::discard,
            onBack = { if (state.isDirty) showDiscardDialog = true else onBack() },
        ),
        modifier = modifier,
    )

    if (showDiscardDialog) {
        DiscardChangesDialog(
            onConfirm = {
                showDiscardDialog = false
                viewModel.discard()
                onBack()
            },
            onDismiss = { showDiscardDialog = false },
        )
    }
}

/** This screen's callbacks, grouped for the same reason [RoutingListActions] is. */
internal data class PerAppActions(
    val onSetMode: (PerAppMode) -> Unit,
    val onToggle: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onSave: () -> Unit,
    val onDiscard: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * The stateless half — see
 * [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s KDoc for why this
 * split exists.
 */
@Composable
internal fun PerAppScreenContent(
    state: PerAppState,
    actions: PerAppActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING)) {
            HeaderRow(onBack = actions.onBack)

            ModeSection(
                mode = state.mode,
                onSetMode = actions.onSetMode,
                modifier = Modifier.padding(top = SECTION_GAP),
            )

            if (state.isEmptyAllowList) {
                EmptyAllowListWarning()
            }
            if (state.isConnected && state.isDirty) {
                ReconnectNotice()
            }

            if (state.mode != PerAppMode.Off) {
                SearchField(
                    query = state.query,
                    onQueryChanged = actions.onSearch,
                    modifier = Modifier.padding(top = SECTION_GAP),
                )
            }

            SelectedCountText(
                count = state.rows.count { it.isSelected },
                modifier = Modifier.padding(top = ROW_GAP),
            )
        }

        AppRowList(
            rows = state.rows,
            onToggle = actions.onToggle,
            modifier = Modifier.weight(1f),
        )

        Button(
            onClick = actions.onSave,
            enabled = state.isDirty && !state.isEmptyAllowList,
            modifier =
            Modifier
                .padding(horizontal = CONTENT_HORIZONTAL_PADDING, vertical = ROW_GAP),
        ) {
            Text(stringResource(R.string.per_app_save))
        }
    }
}

@Composable
private fun HeaderRow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.per_app_back),
            )
        }
        Text(text = stringResource(R.string.per_app_title), style = MaterialTheme.typography.headlineMedium)
    }
}

/** The three [PerAppMode] choices, each a labelled, summarised radio row. */
@Composable
private fun ModeSection(
    mode: PerAppMode,
    onSetMode: (PerAppMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PerAppMode.entries.forEachIndexed { index, entry ->
            ModeRow(
                title = entry.title(),
                summary = entry.summary(),
                selected = entry == mode,
                onSelect = { onSetMode(entry) },
                modifier = if (index == 0) Modifier else Modifier.padding(top = ROW_GAP),
            )
        }
    }
}

@Composable
private fun PerAppMode.title(): String =
    when (this) {
        PerAppMode.Off -> stringResource(R.string.per_app_mode_off)
        PerAppMode.AllowList -> stringResource(R.string.per_app_mode_allow)
        PerAppMode.DenyList -> stringResource(R.string.per_app_mode_deny)
    }

@Composable
private fun PerAppMode.summary(): String =
    when (this) {
        PerAppMode.Off -> stringResource(R.string.per_app_mode_off_summary)
        PerAppMode.AllowList -> stringResource(R.string.per_app_mode_allow_summary)
        PerAppMode.DenyList -> stringResource(R.string.per_app_mode_deny_summary)
    }

@Composable
private fun ModeRow(
    title: String,
    summary: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(ROW_ICON_GAP))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The UI half of [PerAppState.isEmptyAllowList]'s guard (§6.3) — styled like
 * [RuleSetRow]'s own blocking marker, the same red-warning-icon-plus-text
 * shape for the same reason: a control being disabled below is a hint, this
 * text is the explanation.
 */
@Composable
private fun EmptyAllowListWarning(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(MARKER_ICON_SIZE),
        )
        Spacer(Modifier.width(ROW_ICON_GAP / 2))
        Text(
            text = stringResource(R.string.per_app_empty_allow_list),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Spec §7.3: told before committing, not after the tunnel drops. */
@Composable
private fun ReconnectNotice(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.per_app_reconnect_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(top = MARKER_TOP_PADDING),
    )
}

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.per_app_search_hint)) },
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
        singleLine = true,
    )
}

@Composable
private fun SelectedCountText(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = pluralStringResource(R.plurals.per_app_selected_count, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A [LazyColumn] rather than [RoutingListScreenContent]'s scrolling `Column`:
 * this list runs to every installed app, not a handful of rule sets, so only
 * the visible rows may ever compose.
 */
@Composable
private fun AppRowList(
    rows: List<AppRow>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(rows, key = { it.packageName }) { row ->
            AppRowItem(row = row, onToggle = { onToggle(row.packageName) })
        }
    }
}

/**
 * One app: its icon, label and package name, plus [AppRow.isInstalled]'s
 * marker when false — shown, never hidden (spec §7.2). §5.6: the package
 * name is rendered, not logged.
 *
 * The package name is skipped as supporting text when it equals [AppRow.label]
 * outright rather than merely being a substring of it: that equality is
 * exactly [PerAppViewModel]'s own `ghostRows` (there is no other label to give
 * a package no longer installed), and rendering the identical string twice
 * would put two nodes with the same text in the tree for no reader's benefit.
 */
@Composable
private fun AppRowItem(
    row: AppRow,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = ROW_VERTICAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.isSelected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(ROW_ICON_GAP))
        AppIcon(packageName = row.packageName)
        Spacer(Modifier.width(ROW_ICON_GAP))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.label, style = MaterialTheme.typography.bodyLarge)
            if (row.packageName != row.label) {
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!row.isInstalled) {
                Text(
                    text = stringResource(R.string.per_app_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * [packageName]'s launcher icon, resolved from the platform rather than
 * carried on [AppRow] — see [art.yniyniyni.subspace.core.data.InstalledAppsSource]'s
 * own KDoc for why a `Drawable` stays out of the data layer. `null` for a
 * package no longer installed (spec §7.2's ghost rows): nothing to resolve,
 * and the row's own "not installed" text already says so.
 *
 * [Image], not `Icon`: an app's launcher icon is full-colour art, not a
 * monochrome glyph, so it must not be tinted the way `Icon` tints its content.
 */
@Composable
private fun AppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
) {
    val packageManager = LocalContext.current.packageManager
    val icon = remember(packageName) { packageManager.appIconOrNull(packageName) }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier.size(APP_ICON_SIZE))
    } else {
        Spacer(modifier.size(APP_ICON_SIZE))
    }
}

/** `null` for an uninstalled package rather than throwing — the caller has no fallback art to show either way. */
private fun PackageManager.appIconOrNull(packageName: String): ImageBitmap? {
    val drawable =
        try {
            getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}

/**
 * Confirms losing the draft before [PerAppScreen] actually pops — see
 * [PerAppScreen]'s own `showDiscardDialog` comment for why this is raised
 * from there rather than from [PerAppScreenContent].
 */
@Composable
private fun DiscardChangesDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.per_app_discard_title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.per_app_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.per_app_discard_cancel)) }
        },
    )
}
