// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import space.getsub.core.ui.component.SettingRow

/**
 * Diagnostics: the session log viewer (spec §3.4), reached the same way
 * [SettingsScreen]'s existing "Routing" and "Per-app proxy" rows reach their
 * own pushed destinations — a single [SettingRow] navigating out, not a
 * dialog or an inline expansion — plus the per-server traffic breakdown
 * toggle (Task 15) that makes the whole feature reachable in the first
 * place: without this switch the service never emits xray's `stats`/
 * `policy`/`metrics` blocks and Home has nothing to show beneath its
 * traffic tiles.
 *
 * Deliberately **not** a `SectionHeader` plus this content in one
 * composable — [SettingsScreen] places the header itself, the same split
 * [SettingsGeoSection]'s own KDoc documents.
 *
 * The toggle's summary text is the security-relevant part of this section:
 * xray's metrics listener serves `/debug/vars` and the full pprof suite
 * (heap profiles, the process command line, a 30-second CPU profile) from
 * the same `http.ServeMux`, with no flag separating them. Android loopback
 * is not app-isolated, so any other app on the device that finds the port
 * can reach all of it for as long as this stays on — which is why the
 * setting defaults to off (see [space.getsub.core.data.SettingsRepository.perTagBreakdown]'s
 * own KDoc) and why [R.string.settings_breakdown_summary] says so plainly
 * rather than trusting the reader to already know what a "diagnostic port"
 * implies.
 *
 * @param onNavigateToLogViewer forwarded verbatim to `SubspaceNavHost`,
 *   which navigates to `LogViewer` — the same "this screen has no
 *   `NavController` of its own" reasoning [SettingsScreen]'s own
 *   `onNavigateToRouting`/`onNavigateToPerApp` document.
 * @param perTagBreakdown mirrors [space.getsub.core.data.SettingsRepository.perTagBreakdown]
 *   verbatim through [SettingsState] — never a local default, the same reasoning every other
 *   persisted switch on this screen follows.
 * @param perTagBreakdownPendingReconnect mirrors [SettingsState.perTagBreakdownPendingReconnect]
 *   (F2 / ruling R39): true while a running session has not yet picked up the current value of
 *   [perTagBreakdown]. Shown as a notice beneath the switch rather than folded into
 *   [R.string.settings_breakdown_summary] — the summary describes the setting itself and does not
 *   change; this describes a transient fact about *this* session, the same "explains state, does
 *   not restate the control" shape [R.string.settings_dns_overridden] already uses in
 *   [SettingsDnsSection].
 * @param onPerTagBreakdownChanged forwarded verbatim to
 *   [SettingsViewModel.onPerTagBreakdownChanged].
 */
@Composable
internal fun SettingsDiagnosticsSection(
    onNavigateToLogViewer: () -> Unit,
    perTagBreakdown: Boolean,
    perTagBreakdownPendingReconnect: Boolean,
    onPerTagBreakdownChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_logs_title),
            supportingText = stringResource(R.string.settings_logs_summary),
            onClick = onNavigateToLogViewer,
        )

        val breakdownTitle = stringResource(R.string.settings_breakdown_title)
        SettingRow(
            // Distinct from the log row's Info above, and shared with SettingsTunnelSection's
            // failClosed row rather than picked arbitrarily: both are opt-in switches whose own
            // copy is a risk disclosure the user must read before flipping, not a routine
            // preference (see this file's own KDoc on the summary string).
            icon = Icons.Default.Warning,
            label = breakdownTitle,
            supportingText = stringResource(R.string.settings_breakdown_summary),
            // Not cosmetic — see SettingRow's own KDoc and SettingsTunnelSection.kt:64-68 for why
            // a trailing Switch with no ancestor semantics lets a "the switch under this label"
            // assertion pass vacuously, with no real switch to find.
            labelCarriesSemantics = true,
            trailing = {
                Switch(
                    checked = perTagBreakdown,
                    onCheckedChange = onPerTagBreakdownChanged,
                    modifier = Modifier.semantics { contentDescription = breakdownTitle },
                )
            },
        )

        // F2 / ruling R39: the switch's own position is not the whole truth while a session is
        // up — TunnelService.startCore reads this setting once, at connect, and is deliberately
        // not restarted just to apply it (ARCHITECTURE.md §10.4). The on→off direction is the
        // security-relevant one: the running core's unauthenticated metrics/pprof listener
        // (ARCHITECTURE.md §14.4) stays reachable by other apps on the device until reconnect,
        // so this must say so plainly rather than let the switch reading "off" imply that ended.
        if (perTagBreakdownPendingReconnect) {
            val pendingRes =
                if (perTagBreakdown) {
                    R.string.settings_breakdown_pending_on
                } else {
                    R.string.settings_breakdown_pending_off
                }
            Text(text = stringResource(pendingRes), style = MaterialTheme.typography.bodySmall)
        }
    }
}
