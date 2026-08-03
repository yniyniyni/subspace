// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the padding/rotation values below — all
// are tokens/spacing.css's --space-* scale or a plain 0/180 degree rotation
// pair, already named by the val/const each initializes. See ConnectControl.kt
// and FloatingNavigationBar.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.ui.R

private val GROUP_CARD_PADDING = 16.dp
private val GROUP_CARD_HEADER_GAP = 8.dp
private val GROUP_CARD_CONTENT_TOP_PADDING = 8.dp
private const val CARET_ROTATION_COLLAPSED = 0f
private const val CARET_ROTATION_EXPANDED = 180f

/**
 * One folder of profiles on the Servers screen: name, real (unfiltered)
 * profile count, an expand/collapse caret, and an overflow menu for rename,
 * delete and adding a profile.
 *
 * Deliberately does **not** render a quota bar, a provider notice, or
 * Support/Update buttons — those need a subscription's own metadata, which is
 * M4, and a `MANUAL` group has no provider to quote in the first place. Nor
 * does it render an age/last-synced timestamp, for the same reason. This is
 * the container only; [content] supplies the node rows.
 *
 * @param name the group's display name — never a server address, so nothing
 *   here needs §5.6's redaction care.
 * @param profileCount the group's real size, independent of any active
 *   search/filter the caller may be applying to [content] — see
 *   [art.yniyniyni.subspace.feature.profiles.list.ServersGroup.totalProfileCount]'s
 *   own KDoc for why this must not be the filtered count.
 * @param expanded whether [content] is shown. State the caller owns (not this
 *   component), matching [SubspaceBottomSheet]'s `open` convention.
 * @param actions the three overflow-menu callbacks, plus the header's own
 *   expand/collapse toggle — grouped into one carrier the same way
 *   [art.yniyniyni.subspace.feature.home.HomeActions] is. That leaves `name`,
 *   `profileCount`, `expanded`, `actions`, `modifier` and `content`: five
 *   orthogonal, independently-necessary parameters plus the idiomatic
 *   `modifier` slot every composable in this module carries — nothing left to
 *   fold without inventing an artificial grouping, hence the suppression
 *   below rather than a sixth carrier class.
 * @param content the group's node rows, rendered only while [expanded].
 */
@Suppress("LongParameterList")
@Composable
fun GroupCard(
    name: String,
    profileCount: Int,
    expanded: Boolean,
    actions: GroupCardActions,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val toggleDescription =
        stringResource(
            if (expanded) R.string.group_card_collapse_description else R.string.group_card_expand_description,
            name,
            profileCount,
        )
    val caretRotation by
        animateFloatAsState(
            targetValue = if (expanded) CARET_ROTATION_EXPANDED else CARET_ROTATION_COLLAPSED,
            label = "group-card-caret-rotation",
        )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(GROUP_CARD_PADDING)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier =
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClick = actions.onToggleExpand)
                        .semantics { contentDescription = toggleDescription },
                    horizontalArrangement = Arrangement.spacedBy(GROUP_CARD_HEADER_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = pluralStringResource(R.plurals.group_card_profile_count, profileCount, profileCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        // Decorative: the Row's own semantics above already
                        // names the expand/collapse action once.
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer { rotationZ = caretRotation },
                    )
                }

                GroupCardOverflowMenu(groupName = name, actions = actions)
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = GROUP_CARD_CONTENT_TOP_PADDING), content = content)
            }
        }
    }
}

/**
 * [GroupCard]'s four callbacks, grouped into one carrier — see [GroupCard]'s
 * own `actions` parameter KDoc for why.
 *
 * @property onToggleExpand invoked when the caret or header is tapped.
 * @property onRename invoked when "Rename" is chosen from the overflow menu.
 * @property onDelete invoked when "Delete" is chosen — the caller is
 *   responsible for confirming first and stating the group's profile count in
 *   that confirmation, since deleting a group cascades to every profile in it.
 * @property onAddProfile invoked when "Add profile" is chosen from the
 *   overflow menu.
 */
data class GroupCardActions(
    val onToggleExpand: () -> Unit,
    val onRename: () -> Unit,
    val onDelete: () -> Unit,
    val onAddProfile: () -> Unit,
)

@Composable
private fun GroupCardOverflowMenu(
    groupName: String,
    actions: GroupCardActions,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.group_card_overflow_description, groupName),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_card_rename)) },
                onClick = {
                    menuOpen = false
                    actions.onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_card_delete)) },
                onClick = {
                    menuOpen = false
                    actions.onDelete()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.group_card_add_profile)) },
                onClick = {
                    menuOpen = false
                    actions.onAddProfile()
                },
            )
        }
    }
}
