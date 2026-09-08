// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/size tokens below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See ConnectControl.kt and GroupCard.kt for the same pattern.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
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
 * @param labelCarriesSemantics opt-in, defaulting to `false` (every existing call site is
 *   unaffected). This row otherwise lays icon, label and [trailing] out as flat semantics
 *   siblings — confirmed on device (a live `printToLog` dump), a query like
 *   `hasAnyAncestor(hasText(label))` finds no ancestor at all, because nothing here is a real
 *   semantics ancestor of [trailing]'s content. When `true`, [label] is assigned directly to
 *   the row itself via [Modifier.semantics] (not derived by merging descendants), turning the
 *   row into a real ancestor node carrying [label] while [trailing] stays an independently
 *   discoverable descendant — the shape a caller needs to assert "the switch belonging to
 *   *this* label" rather than just "a switch somewhere on screen" (`SettingsTunnelSectionTest`
 *   in `:feature:settings`). The label [Text]'s own semantics are cleared with
 *   [clearAndSetSemantics] in that case so the string is not also discoverable a second time as
 *   its own accessible node, which would turn a plain `onNodeWithText(label)` lookup elsewhere
 *   into an ambiguous multi-match.
 *
 * Seven orthogonal, independently-necessary parameters (including the
 * idiomatic `modifier` slot every composable in this module carries) —
 * nothing left to fold without inventing an artificial grouping the way
 * [GroupCard]'s own `LongParameterList` suppression explains for the
 * identical shape.
 */
@Suppress("LongParameterList")
@Composable
fun SettingRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    onClick: (() -> Unit)? = null,
    labelCarriesSemantics: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val clickableModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    val rowModifier =
        if (labelCarriesSemantics) {
            clickableModifier.semantics { text = AnnotatedString(label) }
        } else {
            clickableModifier
        }

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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = if (labelCarriesSemantics) Modifier.clearAndSetSemantics {} else Modifier,
            )
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
