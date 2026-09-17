// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's GroupCard.kt and SettingRow.kt for the same
// pattern, and feature/routing's PerAppScreen.kt for the identical
// structure this file follows.
@file:Suppress("MagicNumber")

package space.getsub.feature.settings.log

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.ui.theme.RobotoMonoFontFamily
import space.getsub.feature.settings.R

private val CONTENT_HORIZONTAL_PADDING = 24.dp
private val SECTION_GAP = 20.dp
private val LINE_VERTICAL_PADDING = 2.dp
private val LOADING_INDICATOR_SIZE = 32.dp
private val LOADING_TOP_PADDING = 48.dp

/**
 * The session log viewer (spec §3.4): the redacted on-disk ring, tailed
 * manually via [LogViewerActions.onRefresh], with a share action.
 *
 * Reached from Settings' new "Diagnostics" section
 * ([SettingsDiagnosticsSection][space.getsub.feature.settings.SettingsDiagnosticsSection]),
 * a pushed destination like
 * [PerAppScreen][space.getsub.feature.routing.PerAppScreen] and
 * [RoutingListScreen][space.getsub.feature.routing.RoutingListScreen] — the
 * floating nav pill hides while this is on screen, so it carries its own
 * [onBack] the same way those two do.
 *
 * Sharing is a deliberate decision, not an oversight (spec §3.4): every byte
 * [LogViewerState.lines] holds was already redacted at capture, so sending it
 * is exactly as exposing as displaying it on screen, and [RedactionNotice]
 * renders that guarantee so the user can see what they are about to send
 * before they send it. [shareLogText] builds a plain-text `ACTION_SEND` —
 * never a `FileProvider` attachment, since the ring is bounded at ~1 MiB and
 * a text share needs no new provider authority — and nothing leaves the
 * device except through the chooser the user themselves picks; there is no
 * automatic upload and no other network call (`ARCHITECTURE.md` §1
 * non-goals).
 *
 * @param onBack pops back to Settings, exactly like [PerAppScreen]'s own.
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LogViewerViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LogViewerScreenContent(
        state = state,
        actions =
        LogViewerActions(
            onRefresh = viewModel::refresh,
            onClear = viewModel::clear,
            onShare = { shareLogText(context, state.lines) },
            onBack = onBack,
        ),
        modifier = modifier,
    )
}

/**
 * This screen's callbacks, grouped for the same reason
 * [PerAppActions][space.getsub.feature.routing.PerAppActions] is.
 */
internal data class LogViewerActions(
    val onRefresh: () -> Unit,
    val onClear: () -> Unit,
    val onShare: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * The stateless half — see
 * [space.getsub.feature.home.HomeScreenContent]'s KDoc for why this split
 * exists.
 *
 * A [LazyColumn] over [LogViewerState.lines], not a scrolling `Column`
 * materialising every line: the ring is bounded at ~1 MiB (ARCHITECTURE.md
 * §5.3), which is thousands of lines on a long session, so only the visible
 * rows may ever compose — the same reasoning
 * [PerAppScreen][space.getsub.feature.routing.PerAppScreen]'s own
 * `AppRowList` documents.
 */
@Composable
internal fun LogViewerScreenContent(
    state: LogViewerState,
    actions: LogViewerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING)) {
            HeaderRow(actions = actions)
            RedactionNotice(modifier = Modifier.padding(top = SECTION_GAP))
        }

        when {
            state.loading && state.lines.isEmpty() -> LoadingState()
            state.lines.isEmpty() -> EmptyState(modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING))
            else ->
                LogLineList(
                    lines = state.lines,
                    modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = CONTENT_HORIZONTAL_PADDING, vertical = SECTION_GAP / 2),
                )
        }
    }
}

@Composable
private fun HeaderRow(
    actions: LogViewerActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.log_viewer_back),
                )
            }
            Text(text = stringResource(R.string.log_viewer_title), style = MaterialTheme.typography.headlineMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onRefresh) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.log_viewer_refresh),
                )
            }
            IconButton(onClick = actions.onShare) {
                Icon(imageVector = Icons.Default.Share, contentDescription = stringResource(R.string.log_viewer_share))
            }
            IconButton(onClick = actions.onClear) {
                Icon(imageVector = Icons.Default.Clear, contentDescription = stringResource(R.string.log_viewer_clear))
            }
        }
    }
}

/**
 * Spec §3.4: rendered once, above the log itself, so the user sees this
 * guarantee before reaching [HeaderRow]'s share action — never re-derived
 * from [LogViewerState.lines], which arrive already redacted (see
 * [LogViewerState]'s own KDoc).
 */
@Composable
private fun RedactionNotice(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.log_viewer_redaction_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(top = LOADING_TOP_PADDING),
        contentAlignment = Alignment.TopCenter,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(LOADING_INDICATOR_SIZE))
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.log_viewer_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(top = SECTION_GAP),
    )
}

/**
 * One [Text] per line, set in [RobotoMonoFontFamily] — `Type.kt`'s
 * convention for every machine-generated string in this design system, and
 * a captured log line is exactly that (see [StatTile][space.getsub.core.ui.component.StatTile]'s
 * own KDoc for the same rule).
 */
@Composable
private fun LogLineList(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(lines) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = RobotoMonoFontFamily,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(vertical = LINE_VERTICAL_PADDING),
            )
        }
    }
}

/**
 * Spec §3.4: `ACTION_SEND` with the ring's contents as plain text, never a
 * `FileProvider` attachment — see [LogViewerScreen]'s own KDoc for the full
 * reasoning. The chooser lets the user pick where it goes; this function
 * itself makes no network call.
 */
private fun shareLogText(
    context: Context,
    lines: List<String>,
) {
    val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
        }
    val chooserTitle = context.getString(R.string.log_viewer_share)
    context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
}
