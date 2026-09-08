// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens SettingToggleRow reuses from
// core/ui's own SettingRow.kt — same tokens/spacing.css scale, same suppression that file
// documents.
@file:Suppress("MagicNumber")

package space.getsub.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import space.getsub.core.ui.component.SettingRow

private val ROW_PADDING = 16.dp
private val ROW_GAP = 16.dp
private val ICON_TILE_SIZE = 40.dp
private val ICON_SIZE = 20.dp

/**
 * Spec §7, §7.1, §7.2: the settings that keep the tunnel running under the platform —
 * always-on VPN (a deep link, never a switch: see [onOpenVpnSettings]'s own KDoc below),
 * connect-at-startup, the fail-closed kill switch, and battery-optimisation exemption.
 *
 * @param onOpenVpnSettings launches `Settings.ACTION_VPN_SETTINGS`. This app cannot enable
 *   always-on VPN or its "Block connections without VPN" lockdown itself — both are system
 *   settings only the user (or a device policy owner) can set. Rendering this as a switch would
 *   claim ownership of state this app does not have; a row that explains the setting and opens the
 *   real control is the only honest affordance.
 * @param onOpenBatterySettings launches `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
 *   (the settings *list*, not a direct request — see [SettingsScreen]'s wiring for why).
 */
// state plus one callback per row action, plus the idiomatic modifier slot every composable in
// this module carries — the same six-orthogonal-parameters shape SettingRow's own
// LongParameterList suppression documents for the identical count.
@Suppress("LongParameterList")
@Composable
internal fun SettingsTunnelSection(
    state: SettingsState,
    onBootAutostartChange: (Boolean) -> Unit,
    onFailClosedChange: (Boolean) -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_tunnel_title),
            style = MaterialTheme.typography.titleMedium,
        )

        SettingRow(
            icon = Icons.Default.Lock,
            label = stringResource(R.string.settings_always_on_title),
            supportingText = stringResource(R.string.settings_always_on_summary),
            onClick = onOpenVpnSettings,
            trailing = {
                Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
        )

        SettingToggleRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_boot_autostart_title),
            supportingText = stringResource(R.string.settings_boot_autostart_summary),
            checked = state.bootAutostart,
            onCheckedChange = onBootAutostartChange,
        )

        SettingToggleRow(
            icon = Icons.Default.Warning,
            title = stringResource(R.string.settings_fail_closed_title),
            supportingText = stringResource(R.string.settings_fail_closed_summary),
            checked = state.failClosed,
            onCheckedChange = onFailClosedChange,
        )

        SettingRow(
            icon = Icons.Default.Info,
            label = stringResource(R.string.settings_battery_title),
            supportingText = stringResource(R.string.settings_battery_summary),
            onClick = onOpenBatterySettings,
            trailing = {
                Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
        )
    }
}

/**
 * A [SettingRow]-shaped row for a toggle that must be discoverable as a semantic *descendant* of
 * its own label — [SettingRow] itself lays icon, label and trailing switch out as flat semantics
 * siblings (confirmed on device: `SettingsTunnelSectionTest`'s `hasAnyAncestor` assertions found
 * no ancestor at all against a plain `SettingRow`), which is invisible to a sighted user but means
 * no query can ever say "the switch belonging to *this* label" rather than just "a switch
 * somewhere on screen".
 *
 * The fix is this row's own [Modifier.semantics] block: assigning [text] directly to the *row*
 * (not derived by merging descendants) turns the row itself into a real ancestor node carrying
 * [title], while the switch stays a normal, independently-discoverable child beneath it — the
 * `hasAnyAncestor(hasText(title))` shape [SettingsTunnelSectionTest] exercises. The label
 * [Text]'s own semantics are cleared with [clearAndSetSemantics] so the same string is not also
 * discoverable a second time as its own accessible node, which would turn a plain
 * `onNodeWithText(title)` lookup elsewhere into an ambiguous multi-match.
 */
// Same six-orthogonal-parameters shape as SettingRow's own LongParameterList suppression.
@Suppress("LongParameterList")
@Composable
private fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    supportingText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(vertical = ROW_PADDING)
            .semantics { text = AnnotatedString(title) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(ICON_TILE_SIZE),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ICON_SIZE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier =
            Modifier
                .weight(1f)
                .padding(horizontal = ROW_GAP),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}
