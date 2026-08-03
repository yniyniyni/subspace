// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the content paddings below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's ConnectControl.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.feature.profiles.R

private val CONTENT_HORIZONTAL_PADDING = 16.dp
private val FILTER_CHIP_GAP = 8.dp

/**
 * The Servers screen: pick which stored profile Home connects to, and manage
 * the groups that hold them.
 *
 * Search and protocol filtering run in SQL over [art.yniyniyni.subspace.core.data.db.ProfileEntity]'s
 * shadow columns ([ServersViewModel]), not by deserializing every row in
 * memory. Sort ([SortOrder]) has only three entries — a "Fastest" order needs
 * a real latency measurement, and latency testing is M4, so it is absent
 * rather than backed by an invented number (ARCHITECTURE.md §10.1).
 *
 * @param onAddProfile invoked from the empty state and from a group's
 *   overflow menu — this screen has no import UI of its own yet (`:feature:profiles`'s
 *   editor and paste flow are a later task), so this is a navigation hook a
 *   future task wires up, the same shape as [art.yniyniyni.subspace.feature.home.HomeScreen]'s
 *   own `onAddServer`.
 */
@Composable
fun ServersScreen(
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ServersViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    ServersScreenContent(
        state = state,
        actions =
        ServersActions(
            onQueryChanged = viewModel::onQueryChanged,
            onProtocolFilterChanged = viewModel::onProtocolFilterChanged,
            onSortChanged = viewModel::onSortChanged,
            onProfileSelected = viewModel::onProfileSelected,
            onRenameGroup = viewModel::onRenameGroup,
            onDeleteGroup = viewModel::onDeleteGroup,
            onAddProfile = onAddProfile,
        ),
        modifier = modifier,
    )
}

/**
 * [ServersScreenContent]'s seven callbacks, grouped for the same reason
 * [art.yniyniyni.subspace.feature.home.HomeActions] is.
 */
internal data class ServersActions(
    val onQueryChanged: (String) -> Unit,
    val onProtocolFilterChanged: (String) -> Unit,
    val onSortChanged: (SortOrder) -> Unit,
    val onProfileSelected: (Long) -> Unit,
    val onRenameGroup: (Long, String) -> Unit,
    val onDeleteGroup: (Long) -> Unit,
    val onAddProfile: () -> Unit,
)

/**
 * The stateless half — see [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s
 * KDoc for why this split exists.
 */
@Composable
internal fun ServersScreenContent(
    state: ServersState,
    actions: ServersActions,
    modifier: Modifier = Modifier,
) {
    var expandedGroups by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var renameTarget by remember { mutableStateOf<ServersGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<ServersGroup?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.servers_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING, vertical = CONTENT_HORIZONTAL_PADDING),
        )

        ServersFilters(state = state, actions = actions)

        ServersGroupList(
            groups = state.groups,
            expandedGroups = expandedGroups,
            actions =
            ServersGroupListActions(
                onToggleExpand = { id ->
                    expandedGroups = if (id in expandedGroups) expandedGroups - id else expandedGroups + id
                },
                onRename = { renameTarget = it },
                onDelete = { deleteTarget = it },
                onAddProfile = actions.onAddProfile,
                onProfileSelected = actions.onProfileSelected,
            ),
            modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING),
        )
    }

    ServersDialogs(
        renameTarget = renameTarget,
        deleteTarget = deleteTarget,
        actions =
        ServersDialogActions(
            onRenameConfirm = { id, name ->
                actions.onRenameGroup(id, name)
                renameTarget = null
            },
            onRenameDismiss = { renameTarget = null },
            onDeleteConfirm = { id ->
                actions.onDeleteGroup(id)
                deleteTarget = null
            },
            onDeleteDismiss = { deleteTarget = null },
        ),
    )
}

/**
 * The search field, protocol chips and sort control, stacked — split out of
 * [ServersScreenContent] to keep it short.
 */
@Composable
private fun ServersFilters(
    state: ServersState,
    actions: ServersActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SearchField(
            query = state.query,
            onQueryChanged = actions.onQueryChanged,
            modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING),
        )

        ProtocolFilterRow(
            available = state.availableProtocols,
            selected = state.protocolFilter,
            onSelected = actions.onProtocolFilterChanged,
            modifier = Modifier.padding(top = FILTER_CHIP_GAP, start = CONTENT_HORIZONTAL_PADDING),
        )

        SortControl(
            sort = state.sort,
            onSortChanged = actions.onSortChanged,
            modifier = Modifier.padding(horizontal = CONTENT_HORIZONTAL_PADDING),
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.servers_search_placeholder)) },
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.servers_search_clear_description),
                    )
                }
            }
        },
        singleLine = true,
    )
}

@Composable
private fun ProtocolFilterRow(
    available: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(FILTER_CHIP_GAP),
    ) {
        available.forEach { label ->
            FilterChip(selected = label == selected, onClick = { onSelected(label) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SortControl(
    sort: SortOrder,
    onSortChanged: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(onClick = { menuOpen = true }) {
            Text(stringResource(R.string.servers_sort_button, stringResource(sort.labelRes())))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(stringResource(order.labelRes())) },
                    onClick = {
                        menuOpen = false
                        onSortChanged(order)
                    },
                )
            }
        }
    }
}

private fun SortOrder.labelRes(): Int =
    when (this) {
        SortOrder.Alphabetical -> R.string.servers_sort_alphabetical
        SortOrder.AsListed -> R.string.servers_sort_as_listed
        SortOrder.LastUsed -> R.string.servers_sort_last_used
    }
