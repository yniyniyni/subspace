// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.SettingRow

/**
 * Diagnostics: the session log viewer (spec §3.4), reached the same way
 * [SettingsScreen]'s existing "Routing" and "Per-app proxy" rows reach their
 * own pushed destinations — a single [SettingRow] navigating out, not a
 * dialog or an inline expansion.
 *
 * Deliberately its own section file rather than a row folded straight into
 * [SettingsScreenContent]: a later task adds a diagnostics toggle here, and
 * this file is where that control belongs once it exists — see this task's
 * own brief for why growing [SettingsScreenContent] directly was rejected.
 *
 * @param onNavigateToLogViewer forwarded verbatim to `SubspaceNavHost`,
 *   which navigates to `LogViewer` — the same "this screen has no
 *   `NavController` of its own" reasoning [SettingsScreen]'s own
 *   `onNavigateToRouting`/`onNavigateToPerApp` document.
 */
@Composable
internal fun SettingsDiagnosticsSection(
    onNavigateToLogViewer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_logs_title),
            supportingText = stringResource(R.string.settings_logs_summary),
            onClick = onNavigateToLogViewer,
        )
    }
}
