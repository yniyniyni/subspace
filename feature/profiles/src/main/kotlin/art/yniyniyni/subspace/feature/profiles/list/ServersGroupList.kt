// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the empty-state's content padding — a
// tokens/spacing.css --space-* value, already named by the val it
// initializes. See core/ui's ConnectControl.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.ui.component.FLOATING_NAV_CONTENT_BOTTOM_PADDING
import art.yniyniyni.subspace.core.ui.component.GroupCard
import art.yniyniyni.subspace.core.ui.component.GroupCardActions
import art.yniyniyni.subspace.feature.profiles.R

private val LIST_TOP_PADDING = 8.dp
private val GROUP_GAP = 8.dp
private val MEASURE_BAR_GAP = 8.dp

/**
 * [ServersGroupList]'s callbacks, grouped for the same reason
 * [ServersActions] is.
 */
internal data class ServersGroupListActions(
    val onToggleExpand: (Long) -> Unit,
    val onRename: (ServersGroup) -> Unit,
    val onDelete: (ServersGroup) -> Unit,
    val onAddProfile: () -> Unit,
    val onProfileSelected: (Long) -> Unit,
    /** Task 21: the row's edit icon — opens `Editor(profileId)`, distinct from [onProfileSelected]. */
    val onProfileEdit: (Long) -> Unit,
    /**
     * Fix round, Important 1: `GroupCard`'s Update button — runs one sync of
     * the subscription id [ServersGroup.subscriptionId] names. Only ever
     * invoked for a `SUBSCRIPTION` group, since [subscriptionId] is null for
     * a `MANUAL` one and the call site below never builds a `GroupCard`
     * `onUpdate` lambda in that case.
     */
    val onUpdateSubscription: (Long) -> Unit,
    /**
     * Task 15: `GroupCard`'s "Subscription details" overflow item — opens
     * `SubscriptionDetail(subscriptionId)`. Same "only ever invoked for a
     * `SUBSCRIPTION` group" reasoning as [onUpdateSubscription].
     */
    val onOpenSubscriptionDetail: (Long) -> Unit,
    /** A row's test button — measures that one server. */
    val onTestProfile: (Long) -> Unit,
    /** A group's "Test all" — measures the rows currently visible in it. */
    val onTestGroup: (Long) -> Unit,
    /** Stops the run in flight. Rows still testing return to idle, not to a result. */
    val onCancelTests: () -> Unit,
    /** This group's own order, which outranks its provider's (§A.1). */
    val onGroupSortChanged: (Long, SortOrder) -> Unit,
)

/**
 * Every group as a [GroupCard], or the empty state when [groups] is empty —
 * a genuinely empty store (nothing imported yet), not a search/filter that
 * happens to match nothing (a group with zero matching rows still renders,
 * with an empty [ServersGroup.profiles] list — see that property's own KDoc).
 */
@Composable
internal fun ServersGroupList(
    groups: List<ServersGroup>,
    expandedGroups: Set<Long>,
    actions: ServersGroupListActions,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) {
        EmptyServersState(onAddProfile = actions.onAddProfile, modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
        PaddingValues(
            top = LIST_TOP_PADDING,
            // The list scrolls, so it — not the caller's screen host —
            // reserves the space the floating nav pill floats over. See
            // FLOATING_NAV_CONTENT_BOTTOM_PADDING's own KDoc.
            bottom = FLOATING_NAV_CONTENT_BOTTOM_PADDING,
        ),
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        items(groups, key = { it.id }) { group ->
            GroupCard(
                name = group.name,
                profileCount = group.totalProfileCount,
                expanded = group.id in expandedGroups,
                actions =
                GroupCardActions(
                    onToggleExpand = { actions.onToggleExpand(group.id) },
                    onRename = { actions.onRename(group) },
                    onDelete = { actions.onDelete(group) },
                    onAddProfile = actions.onAddProfile,
                ),
                quotaUsedBytes = group.quotaUsedBytes,
                quotaTotalBytes = group.quotaTotalBytes,
                lastFetchedAtEpochMillis = group.lastFetchedAtEpochMillis,
                // null for a MANUAL group (subscriptionId is null there) —
                // GroupCard draws no Update affordance without an id to sync.
                onUpdate = group.subscriptionId?.let { id -> { actions.onUpdateSubscription(id) } },
                // Task 15: same null-for-MANUAL reasoning as onUpdate just above — a MANUAL
                // group has no subscription detail screen to open.
                onOpenDetail = group.subscriptionId?.let { id -> { actions.onOpenSubscriptionDetail(id) } },
            ) {
                GroupMeasureBar(
                    group = group,
                    onTestGroup = { actions.onTestGroup(group.id) },
                    onCancelTests = actions.onCancelTests,
                    onSortChanged = { order -> actions.onGroupSortChanged(group.id, order) },
                )
                group.profiles.forEach { row ->
                    ServerRowItem(
                        row = row,
                        onSelect = { actions.onProfileSelected(row.id) },
                        onEdit = { actions.onProfileEdit(row.id) },
                        onTest = { actions.onTestProfile(row.id) },
                    )
                }
            }
        }
    }
}

/**
 * This group's measurement and ordering controls.
 *
 * Sort lives here, per group, rather than only on the screen: §A.1 scopes
 * `subscriptions-sort-type` to the subscription that delivered it, so one global
 * control would let provider A rearrange provider B's rows. When the order came
 * from the provider the group says so — §A.1 requires that precedence to be
 * *visible*, not merely correct — and picking an order here clears the marker,
 * because the user's own choice outranks it.
 *
 * The button flips to "Stop testing" while any of this group's rows are in
 * flight. Cancelling is not instantaneous (a measurement already inside
 * libXray's blocking ping finishes on its own), so the rows it stops simply
 * return to idle rather than showing a result that never arrived.
 */
@Composable
private fun GroupMeasureBar(
    group: ServersGroup,
    onTestGroup: () -> Unit,
    onCancelTests: () -> Unit,
    onSortChanged: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val testing = group.profiles.any { it.isTesting }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MEASURE_BAR_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val actionLabel =
            stringResource(if (testing) R.string.servers_test_cancel else R.string.servers_test_group)
        TextButton(onClick = if (testing) onCancelTests else onTestGroup) {
            Text(text = actionLabel)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (group.sortFromProvider) {
            Text(
                text = stringResource(R.string.servers_sort_from_provider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SortControl(sort = group.sort, onSortChanged = onSortChanged)
    }
}

@Composable
private fun EmptyServersState(
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.servers_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.servers_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onAddProfile) {
            Text(stringResource(R.string.servers_empty_action))
        }
    }
}
