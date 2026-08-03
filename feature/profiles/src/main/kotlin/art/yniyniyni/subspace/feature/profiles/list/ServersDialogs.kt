// SPDX-License-Identifier: AGPL-3.0-or-later
// The file is named for ServersDialogs, the entry point every other file in
// this package calls — detekt's MatchingDeclarationName instead wants it
// named after ServersDialogActions, the one *class* here, ignoring that the
// file's three functions (ServersDialogs, RenameGroupDialog, DeleteGroupDialog)
// outnumber it. See ServersScreen.kt/ServersGroupList.kt for the same
// entry-point-plus-action-carrier shape.
@file:Suppress("MatchingDeclarationName")

package art.yniyniyni.subspace.feature.profiles.list

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import art.yniyniyni.subspace.feature.profiles.R

/**
 * [ServersDialogs]' four callbacks, grouped for the same reason
 * [ServersActions] is.
 */
internal data class ServersDialogActions(
    val onRenameConfirm: (Long, String) -> Unit,
    val onRenameDismiss: () -> Unit,
    val onDeleteConfirm: (Long) -> Unit,
    val onDeleteDismiss: () -> Unit,
)

/**
 * Renders the rename and/or delete confirmation for whichever group is
 * currently targeted — `null` renders nothing, the same "closed means absent
 * from composition" convention [art.yniyniyni.subspace.core.ui.component.SubspaceBottomSheet]
 * uses for its own `open` flag.
 */
@Composable
internal fun ServersDialogs(
    renameTarget: ServersGroup?,
    deleteTarget: ServersGroup?,
    actions: ServersDialogActions,
) {
    renameTarget?.let { group ->
        RenameGroupDialog(
            group = group,
            onConfirm = { newName -> actions.onRenameConfirm(group.id, newName) },
            onDismiss = actions.onRenameDismiss,
        )
    }

    deleteTarget?.let { group ->
        DeleteGroupDialog(
            group = group,
            onConfirm = { actions.onDeleteConfirm(group.id) },
            onDismiss = actions.onDeleteDismiss,
        )
    }
}

@Composable
private fun RenameGroupDialog(
    group: ServersGroup,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(group.id) { mutableStateOf(group.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_rename_group_title)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.servers_rename_group_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.servers_dialog_cancel)) } },
    )
}

/**
 * Warns before a group is removed — the FK cascades, so this states
 * [ServersGroup.totalProfileCount] rather than leaving the blast radius
 * implicit.
 */
@Composable
private fun DeleteGroupDialog(
    group: ServersGroup,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_delete_group_title, group.name)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.servers_delete_group_message,
                    group.totalProfileCount,
                    group.totalProfileCount,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.servers_delete_group_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.servers_dialog_cancel)) }
        },
    )
}
