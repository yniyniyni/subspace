// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.PingMode
import space.getsub.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import space.getsub.core.ui.component.SectionHeader
import space.getsub.core.ui.component.SettingRow

/** Aligns the HWID value with [SettingRow]'s label column: its 40.dp icon tile plus a 16.dp gap. */
private val HWID_VALUE_START_PADDING = 56.dp
private val HWID_VALUE_BOTTOM_PADDING = 8.dp

/**
 * The Settings screen: Appearance, Device ID, Latency testing, Routing, Geo databases and About.
 *
 * **Not drawn**, each because a later milestone owns it, not because it was
 * forgotten:
 *  - Always-on VPN and a log viewer — M7.
 *
 * None of these get a stub, a disabled row, or a "coming soon" entry — an
 * empty control that looks like a feature is worse than no control at all.
 * Routing itself is drawn as of Task 15 (M5): a single row that navigates to
 * `RoutingList` (`:feature:routing`) rather than a stub, since that screen is
 * real and reachable now. Per-app proxy is drawn the same way as of Task 9
 * (M5.5): a row beneath it that navigates to `PerApp` (`:feature:routing`).
 *
 * Theme selection here does not (yet) repaint [space.getsub.core.ui.theme.SubspaceTheme]
 * itself — `MainActivity` still always renders with the system setting.
 * Wiring that through is a rendering concern for whichever task first needs
 * it; this task's scope is the setting existing and surviving a restart, which
 * [SettingsViewModel]'s own test proves.
 *
 * @param onNavigateToRouting the "Routing" row's action, forwarded verbatim to
 *   `SubspaceNavHost`, which navigates to `RoutingList` — the same "this screen has no
 *   `NavController` of its own" reasoning
 *   [ServersScreen][space.getsub.feature.profiles.list.ServersScreen]'s own navigation
 *   callbacks document.
 * @param onNavigateToPerApp the "Per-app proxy" row's action, forwarded verbatim to
 *   `SubspaceNavHost`, which navigates to `PerApp` — the same reasoning as
 *   [onNavigateToRouting].
 */
@Composable
fun SettingsScreen(
    onNavigateToRouting: () -> Unit,
    onNavigateToPerApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScreenContent(
        state = state,
        actions =
        SettingsActions(
            onThemeChanged = viewModel::onThemeChanged,
            onHwidEnabledChanged = viewModel::onHwidEnabledChanged,
            onPingModeChanged = viewModel::onPingModeChanged,
            onPingCheckUrlChanged = viewModel::onPingCheckUrlChanged,
            onPingTimeoutChanged = viewModel::onPingTimeoutChanged,
            onPingOnLaunchChanged = viewModel::onPingOnLaunchChanged,
            onPingOnLaunchMeteredChanged = viewModel::onPingOnLaunchMeteredChanged,
            onDnsTransportChanged = viewModel::onDnsTransportChanged,
            onDnsAddressChanged = viewModel::onDnsAddressChanged,
            onDnsBootstrapIpChanged = viewModel::onDnsBootstrapIpChanged,
            onGeoSourceSelected = viewModel::onGeoSourceSelected,
            onGeoUpdateNow = viewModel::onGeoUpdateNow,
            onAddCustomGeoSource = viewModel::onAddCustomGeoSource,
            onGeoRefreshOnMeteredChanged = viewModel::onGeoRefreshOnMeteredChanged,
            onRemoveCustomGeoSource = viewModel::onRemoveCustomGeoSource,
            onBootAutostartChanged = viewModel::onBootAutostartChanged,
            onFailClosedChanged = viewModel::onFailClosedChanged,
            onBatteryPromptResolved = viewModel::onBatteryPromptResolved,
        ),
        onNavigateToRouting = onNavigateToRouting,
        onNavigateToPerApp = onNavigateToPerApp,
        modifier = modifier,
    )
}

/**
 * This screen's callbacks, grouped for the same reason
 * [HomeActions][space.getsub.feature.home.HomeActions] is.
 *
 * A carrier only became worth it with M4.5: two callbacks were fine as
 * parameters, seven would be a `LongParameterList` finding and would leave every
 * call site positional.
 */
internal data class SettingsActions(
    val onThemeChanged: (ThemePreference) -> Unit,
    val onHwidEnabledChanged: (Boolean) -> Unit,
    val onPingModeChanged: (PingMode) -> Unit,
    val onPingCheckUrlChanged: (String) -> Unit,
    val onPingTimeoutChanged: (Int) -> Unit,
    val onPingOnLaunchChanged: (Boolean) -> Unit,
    val onPingOnLaunchMeteredChanged: (Boolean) -> Unit,
    val onDnsTransportChanged: (DnsTransport) -> Unit,
    val onDnsAddressChanged: (String) -> Unit,
    val onDnsBootstrapIpChanged: (String) -> Unit,
    val onGeoSourceSelected: (String) -> Unit,
    val onGeoUpdateNow: (GeoRow) -> Unit,
    val onAddCustomGeoSource: (url: String, fileName: String, geoType: GeoDataKind) -> Unit,
    val onGeoRefreshOnMeteredChanged: (Boolean) -> Unit,
    val onRemoveCustomGeoSource: (String) -> Unit,
    // Defaulted (unlike every field above): Task 12 landed after SettingsHwidLayoutTest's own
    // full positional SettingsActions(...) construction, and neither of these two setters bears
    // on what that test asserts (HWID layout) — a default avoids editing an unrelated file's test
    // fixture for a field it does not exercise.
    val onBootAutostartChanged: (Boolean) -> Unit = {},
    val onFailClosedChanged: (Boolean) -> Unit = {},
    val onBatteryPromptResolved: () -> Unit = {},
)

/**
 * The stateless half — see [space.getsub.feature.home.HomeScreenContent]'s
 * KDoc for why this split exists.
 */
@Composable
internal fun SettingsScreenContent(
    state: SettingsState,
    actions: SettingsActions,
    onNavigateToRouting: () -> Unit,
    onNavigateToPerApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onThemeChanged = actions.onThemeChanged
    val onHwidEnabledChanged = actions.onHwidEnabledChanged
    val context = LocalContext.current
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            // Settings scrolls, so it — not FloatingNavigationBar — reserves
            // the space the pill floats over. See
            // FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc.
            .padding(top = 24.dp, bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING),
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)

        SectionHeader(stringResource(R.string.settings_section_appearance))
        AppearanceControl(selected = state.theme, onThemeChanged = onThemeChanged)

        SectionHeader(stringResource(R.string.settings_section_device_id))
        HwidControl(
            enabled = state.hwidEnabled,
            hwid = state.hwid,
            onEnabledChanged = onHwidEnabledChanged,
        )

        SectionHeader(stringResource(R.string.settings_latency_section))
        LatencyControls(state = state, actions = actions)

        SettingsDnsSection(state = state, actions = actions)

        TunnelSection(state = state, actions = actions, context = context)

        SectionHeader(stringResource(R.string.settings_section_routing))
        SettingRow(
            icon = Icons.AutoMirrored.Filled.List,
            label = stringResource(R.string.settings_routing_row_label),
            supportingText = stringResource(R.string.settings_routing_row_summary),
            onClick = onNavigateToRouting,
        )
        SettingRow(
            icon = Icons.Filled.CheckCircle,
            label = stringResource(R.string.settings_per_app_row_label),
            supportingText = stringResource(R.string.settings_per_app_row_summary),
            onClick = onNavigateToPerApp,
        )

        SectionHeader(stringResource(R.string.settings_section_geo))
        SettingsGeoSection(state = state, actions = actions)

        SectionHeader(stringResource(R.string.settings_section_about))
        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_about_app_version_label),
            supportingText = state.appVersion,
        )
        SettingRow(
            icon = Icons.Default.Build,
            label = stringResource(R.string.settings_about_xray_version_label),
            supportingText = state.xrayVersion.displayText(),
        )
        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_about_license_label),
            supportingText = stringResource(R.string.settings_about_license_value),
        )
    }
}

/**
 * System/Light/Dark as a single-choice segmented control — Material3's own
 * selection widget for exactly three mutually-exclusive, always-visible
 * options, so this needs no picker sheet and no per-option icon (the
 * -core-only icon set — see THIRD_PARTY.md — has no light/dark glyph pair
 * to draw one with anyway).
 */
@Composable
private fun AppearanceControl(
    selected: ThemePreference,
    onThemeChanged: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        ThemePreference.entries.forEachIndexed { index, option ->
            val isSelected = option == selected
            val description =
                stringResource(
                    if (isSelected) {
                        R.string.settings_theme_option_selected_description
                    } else {
                        R.string.settings_theme_option_description
                    },
                    stringResource(option.labelRes()),
                )
            SegmentedButton(
                selected = isSelected,
                onClick = { onThemeChanged(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemePreference.entries.size),
                modifier = Modifier.semantics { contentDescription = description },
                label = { Text(stringResource(option.labelRes())) },
            )
        }
    }
}

@Composable
private fun HwidControl(
    enabled: Boolean,
    hwid: String,
    onEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.settings_hwid_title)
    SettingRow(
        icon = Icons.Default.Lock,
        label = title,
        supportingText = stringResource(R.string.settings_hwid_summary),
        trailing = {
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                modifier = Modifier.semantics { contentDescription = title },
            )
        },
        modifier = modifier,
    )
    // The HWID is 43 unbreakable monospace characters, so it cannot live in SettingRow's
    // `trailing` slot: that slot is measured at its intrinsic width before the label column's
    // weight(1f) is resolved, so the value claimed the whole row and squeezed the label to about
    // one character, rendering it as a vertical stack of letters. Found on device. It is a
    // full-width value, not a control — so it goes on its own line beneath the row.
    SettingRow(
        icon = Icons.Default.Lock,
        label = stringResource(R.string.settings_hwid_value_title),
        supportingText = stringResource(R.string.settings_hwid_value_summary),
    )
    SelectionContainer {
        Text(
            text = hwid,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = HWID_VALUE_START_PADDING, bottom = HWID_VALUE_BOTTOM_PADDING),
        )
    }
}

/**
 * A thin wrapper around [SettingsTunnelSection] so [SettingsScreenContent] itself stays under
 * detekt's `LongMethod` threshold — no separate [SectionHeader] call here, since
 * [SettingsTunnelSection] renders its own "Tunnel" title internally, the same way
 * [SettingsDnsSection] does for "DNS".
 *
 * Also hosts the battery-optimisation prompt (Task 13, spec §7.2): [SettingsState.showBatteryPrompt]
 * is true for the one moment between a survival setting being switched on and the user responding.
 * Both the dialog's own dismiss and its "open battery settings" action resolve through
 * [SettingsActions.onBatteryPromptResolved] — ARCHITECTURE.md §9's "respect refusal" means the
 * prompt is marked seen whatever the user chooses, not only on acceptance.
 */
@Composable
private fun TunnelSection(
    state: SettingsState,
    actions: SettingsActions,
    context: Context,
) {
    SettingsTunnelSection(
        state = state,
        onBootAutostartChange = actions.onBootAutostartChanged,
        onFailClosedChange = actions.onFailClosedChanged,
        onOpenVpnSettings = { openVpnSettings(context) },
        onOpenBatterySettings = { openBatterySettings(context) },
    )

    if (state.showBatteryPrompt) {
        AlertDialog(
            onDismissRequest = actions.onBatteryPromptResolved,
            title = { Text(stringResource(R.string.settings_battery_prompt_title)) },
            text = { Text(stringResource(R.string.settings_battery_prompt_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        actions.onBatteryPromptResolved()
                        openBatterySettings(context)
                    },
                ) {
                    Text(stringResource(R.string.settings_battery_prompt_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onBatteryPromptResolved) {
                    Text(stringResource(R.string.settings_battery_prompt_dismiss))
                }
            },
        )
    }
}

private fun ThemePreference.labelRes(): Int =
    when (this) {
        ThemePreference.System -> R.string.settings_theme_system
        ThemePreference.Light -> R.string.settings_theme_light
        ThemePreference.Dark -> R.string.settings_theme_dark
    }

@Composable
private fun XrayVersionState.displayText(): String =
    when (this) {
        XrayVersionState.Loading -> stringResource(R.string.settings_about_xray_version_loading)
        is XrayVersionState.Available -> version
        XrayVersionState.Unavailable -> stringResource(R.string.settings_about_xray_version_unavailable)
    }

/** Spec §7.1: the app cannot set always-on VPN or its lockdown itself — both are system settings. */
private fun openVpnSettings(context: Context) {
    launchSettingsIntent(context, Settings.ACTION_VPN_SETTINGS)
}

/**
 * Spec §7.2: the settings *list*, not `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — that direct
 * prompt needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, one of the most
 * policy-sensitive on Android, and ARCHITECTURE.md §14.7 says nothing here should foreclose Google
 * Play. One extra tap is cheaper than that permission, and no new permission is declared for it.
 */
private fun openBatterySettings(context: Context) {
    launchSettingsIntent(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

/**
 * Both settings rows launch an implicit system intent this app does not control the target of —
 * [FLAG_ACTIVITY_NEW_TASK][Intent.FLAG_ACTIVITY_NEW_TASK] since [context] here is not itself an
 * `Activity`, and a caught [ActivityNotFoundException] rather than an uncaught crash for the rare
 * OEM build missing the target settings Activity: a row that only ever opens elsewhere must not be
 * able to take the whole settings screen down with it.
 */
private fun launchSettingsIntent(context: Context, action: String) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No target Activity on this device/build — nothing more this row can do.
    }
}
