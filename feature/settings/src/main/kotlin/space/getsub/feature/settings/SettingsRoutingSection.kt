// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import space.getsub.core.ui.component.SectionHeader
import space.getsub.core.ui.component.SettingRow

/**
 * "Routing" section: rule sets and per-app proxy, each a single row that
 * pushes a real `:feature:routing` screen — extracted out of
 * [SettingsScreenContent] the same way [SettingsDnsSection] and
 * [SettingsGeoSection] were, both for `LongMethod` and because adding this
 * task's [SettingsNavigation] parameter pushed [SettingsScreenContent] past
 * detekt's `LongParameterList`/`LongMethod` thresholds together.
 */
@Composable
internal fun SettingsRoutingSection(
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(stringResource(R.string.settings_section_routing))
        SettingRow(
            icon = Icons.AutoMirrored.Filled.List,
            label = stringResource(R.string.settings_routing_row_label),
            supportingText = stringResource(R.string.settings_routing_row_summary),
            onClick = navigation.onNavigateToRouting,
        )
        SettingRow(
            icon = Icons.Filled.CheckCircle,
            label = stringResource(R.string.settings_per_app_row_label),
            supportingText = stringResource(R.string.settings_per_app_row_summary),
            onClick = navigation.onNavigateToPerApp,
        )
    }
}
