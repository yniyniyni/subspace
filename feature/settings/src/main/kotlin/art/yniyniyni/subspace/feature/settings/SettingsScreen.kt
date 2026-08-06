// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import art.yniyniyni.subspace.core.ui.component.SectionHeader
import art.yniyniyni.subspace.core.ui.component.SettingRow

/**
 * The Settings screen: Appearance and About, deliberately nothing else.
 *
 * **Not drawn**, each because a later milestone owns it, not because it was
 * forgotten:
 *  - Always-on VPN and a log viewer — M7.
 *  - Per-app proxy and routing rules — M5.
 *  - Subscriptions, HWID, ping-on-connect and a refresh interval — M4.
 *
 * None of these get a stub, a disabled row, or a "coming soon" entry — an
 * empty control that looks like a feature is worse than no control at all.
 *
 * Theme selection here does not (yet) repaint [art.yniyniyni.subspace.core.ui.theme.SubspaceTheme]
 * itself — `MainActivity` still always renders with the system setting.
 * Wiring that through is a rendering concern for whichever task first needs
 * it; this task's scope is the setting existing and surviving a restart, which
 * [SettingsViewModel]'s own test proves.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SettingsScreenContent(
        state = state,
        onThemeChanged = viewModel::onThemeChanged,
        modifier = modifier,
    )
}

/**
 * The stateless half — see [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s
 * KDoc for why this split exists. A single callback ([onThemeChanged]) needs
 * no [HomeActions][art.yniyniyni.subspace.feature.home.HomeActions]-style
 * carrier — there is nothing to group it with.
 */
@Composable
internal fun SettingsScreenContent(
    state: SettingsState,
    onThemeChanged: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier,
) {
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
