// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See ConnectControl.kt and GroupCard.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val ROW_PADDING = 16.dp
private val ROW_GAP = 16.dp
private val ICON_TILE_SIZE = 40.dp
private val ICON_SIZE = 20.dp

/**
 * One row of a settings screen: an icon tile on the leading edge, a [label]
 * and optional [supportingText] in the middle, and whatever [trailing] the
 * caller supplies on the end — a chevron for a row that opens something, a
 * `Switch` for a toggle, a checkmark for the currently-selected choice in a
 * group, or nothing for a purely informational row (About's version rows).
 *
 * Built for Settings' own two sections, but the shape is generic on purpose:
 * the M4 subscription detail screen (subscription name, quota, refresh
 * status) reuses it rather than growing a second, near-identical row
 * component.
 *
 * [icon] is decorative — `contentDescription = null` on the glyph itself —
 * because [label] already names the row for a screen reader via Compose's
 * default semantics merging, the same reasoning [GroupCard]'s caret icon
 * documents.
 *
 * @param onClick `null` renders a static, non-interactive row (About's
 *   version rows); non-null makes the whole row clickable (Appearance's
 *   three theme choices).
 *
 * Six orthogonal, independently-necessary parameters (including the
 * idiomatic `modifier` slot every composable in this module carries) —
 * nothing left to fold without inventing an artificial grouping the way
 * [GroupCard]'s own `LongParameterList` suppression explains for the
 * identical count.
 */
@Suppress("LongParameterList")
@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier

    Row(
        modifier = rowModifier.fillMaxWidth().padding(vertical = ROW_PADDING),
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
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        trailing?.invoke()
    }
}
