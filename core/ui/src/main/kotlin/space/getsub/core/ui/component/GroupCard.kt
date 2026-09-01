// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the padding/rotation values below — all
// are tokens/spacing.css's --space-* scale or a plain 0/180 degree rotation
// pair, already named by the val/const each initializes. See ConnectControl.kt
// and FloatingNavigationBar.kt for the same pattern.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Refresh
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
import space.getsub.core.ui.R

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
 * Renders a [QuotaBar] beneath the header when [quotaUsedBytes]/
 * [quotaTotalBytes] are given and measurable — a `SUBSCRIPTION` group whose
 * provider sent a usable `subscription-userinfo`. Below that, a last-fetch
 * age (from [lastFetchedAtEpochMillis]) and an Update button (from
 * [onUpdate]) render independently of each other and of the quota bar —
 * each is `null`-defaulted on its own, so a group can show any subset. Every
 * pre-existing `MANUAL`-group call site is unchanged (every one of these
 * four defaults to `null`), and a `MANUAL` group — which has no subscription
 * to quote in the first place — simply never passes any of them. [QuotaBar]'s
 * own contract (not a check duplicated here) already renders nothing for an
 * absent or unlimited total, so this card does not need to re-derive that
 * condition.
 *
 * A provider notice is still unrendered: `DirectiveRegistry` gates
 * `announce`/`sub-info-text` (the only real content source for one) to a
 * later milestone (`Consumer.Release`), so M4 has nothing to put in one without
 * inventing content — left for the task that consumes those directives. This
 * is the container only; [content] supplies the node rows.
 *
 * @param name the group's display name — never a server address, so nothing
 *   here needs §5.6's redaction care.
 * @param profileCount the group's real size, independent of any active
 *   search/filter the caller may be applying to [content] — see
 *   [space.getsub.feature.profiles.list.ServersGroup.totalProfileCount]'s
 *   own KDoc for why this must not be the filtered count.
 * @param expanded whether [content] is shown. State the caller owns (not this
 *   component), matching [SubspaceBottomSheet]'s `open` convention.
 * @param actions the three overflow-menu callbacks, plus the header's own
 *   expand/collapse toggle — grouped into one carrier the same way
 *   [space.getsub.feature.home.HomeActions] is.
 * @param quotaUsedBytes bytes already consumed, forwarded to [QuotaBar] —
 *   `null` for a `MANUAL` group or a `SUBSCRIPTION` group whose provider sent
 *   no measurable usage.
 * @param quotaTotalBytes the plan's cap in bytes, forwarded to [QuotaBar] —
 *   `null` for a `MANUAL` group or a `SUBSCRIPTION` group whose provider sent
 *   no `total` field.
 * @param lastFetchedAtEpochMillis when this group's subscription was last
 *   successfully fetched, rendered as a relative time (`"3 hours ago"`, via
 *   [DateUtils.getRelativeTimeSpanString] — the same platform formatter
 *   [space.getsub.feature.home.HomeScreen] already uses for a
 *   related need, so this deliberately does not invent a second one). `null`
 *   for a `MANUAL` group or a subscription never yet successfully fetched.
 * @param onUpdate invoked when the Update button is tapped — runs one sync of
 *   this group's subscription right now. `null` renders no Update button; a
 *   `MANUAL` group has no subscription to sync, so its call site never
 *   supplies this.
 * @param onOpenDetail Task 15: invoked when "Subscription details" is chosen from the overflow
 *   menu — opens the subscription detail screen. `null` renders no such item, same "a `MANUAL`
 *   group has nothing to open" reasoning [onUpdate] documents; its call site never supplies this
 *   for a `MANUAL` group either.
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
    quotaUsedBytes: Long? = null,
    quotaTotalBytes: Long? = null,
    lastFetchedAtEpochMillis: Long? = null,
    onUpdate: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(GROUP_CARD_PADDING)) {
            GroupCardHeader(
                state =
                GroupCardHeaderState(
                    name = name,
                    profileCount = profileCount,
                    expanded = expanded,
                    onOpenDetail = onOpenDetail,
                ),
                actions = actions,
            )

            QuotaBar(
                usedBytes = quotaUsedBytes,
                totalBytes = quotaTotalBytes,
                modifier = Modifier.padding(top = GROUP_CARD_CONTENT_TOP_PADDING),
            )

            if (lastFetchedAtEpochMillis != null || onUpdate != null) {
                GroupCardUpdateRow(
                    groupName = name,
                    lastFetchedAtEpochMillis = lastFetchedAtEpochMillis,
                    onUpdate = onUpdate,
                    modifier = Modifier.padding(top = GROUP_CARD_CONTENT_TOP_PADDING),
                )
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(top = GROUP_CARD_CONTENT_TOP_PADDING), content = content)
            }
        }
    }
}

/**
 * The name/count/caret/overflow-menu row — split out of [GroupCard] itself
 * (fix round: adding the quota/update slots pushed that function past
 * detekt's `LongMethod` line budget) so this is purely an extraction, not a
 * behaviour change; every line below is unmodified from [GroupCard]'s
 * previous body.
 */
@Composable
private fun GroupCardHeader(
    state: GroupCardHeaderState,
    actions: GroupCardActions,
    modifier: Modifier = Modifier,
) {
    val toggleDescription =
        stringResource(
            if (state.expanded) R.string.group_card_collapse_description else R.string.group_card_expand_description,
            state.name,
            state.profileCount,
        )
    val caretRotation by
        animateFloatAsState(
            targetValue = if (state.expanded) CARET_ROTATION_EXPANDED else CARET_ROTATION_COLLAPSED,
            label = "group-card-caret-rotation",
        )

    Row(
        modifier = modifier.fillMaxWidth(),
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
                Text(text = state.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = pluralStringResource(
                        R.plurals.group_card_profile_count,
                        state.profileCount,
                        state.profileCount,
                    ),
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

        GroupCardOverflowMenu(
            groupName = state.name,
            actions = actions,
            onOpenDetail = state.onOpenDetail,
        )
    }
}

private data class GroupCardHeaderState(
    val name: String,
    val profileCount: Int,
    val expanded: Boolean,
    val onOpenDetail: (() -> Unit)?,
)

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

/**
 * The last-fetch age and Update button row — fix round, Important 1/2.
 * Either half renders independently: [lastFetchedAtEpochMillis] `null` hides
 * the age text, [onUpdate] `null` hides the button, and (per [GroupCard]'s
 * own call site) this whole row is skipped when both are `null`.
 *
 * [lastFetchedAtEpochMillis] is formatted with the platform's own
 * [DateUtils.getRelativeTimeSpanString] rather than a bespoke "N hours ago"
 * implementation — deliberately minimal per this fix round's own guidance,
 * and the same formatter [space.getsub.feature.home.HomeScreen]
 * already uses for connected-uptime, so this is not a second one-off.
 */
@Composable
private fun GroupCardUpdateRow(
    groupName: String,
    lastFetchedAtEpochMillis: Long?,
    onUpdate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lastFetchedAtEpochMillis != null) {
            val relative =
                DateUtils.getRelativeTimeSpanString(
                    lastFetchedAtEpochMillis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString()
            Text(
                text = stringResource(R.string.group_card_last_updated, relative),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onUpdate != null) {
            IconButton(onClick = onUpdate) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.group_card_update_description, groupName),
                )
            }
        }
    }
}

@Composable
private fun GroupCardOverflowMenu(
    groupName: String,
    actions: GroupCardActions,
    onOpenDetail: (() -> Unit)?,
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
            if (onOpenDetail != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_card_open_detail)) },
                    onClick = {
                        menuOpen = false
                        onOpenDetail()
                    },
                )
            }
        }
    }
}
