// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the paddings/gaps below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's ConnectControl.kt for the same pattern.
@file:Suppress(
    "MagicNumber",
    // Stateless Compose helpers keep each detail row focused and directly testable. Splitting
    // them into artificial files would hide their shared SubscriptionDetailContent contract.
    "TooManyFunctions",
)

package space.getsub.feature.profiles.subscription

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import space.getsub.core.data.isDirectiveEnabled
import space.getsub.core.ui.component.QuotaBar
import space.getsub.core.ui.component.SettingRow
import space.getsub.feature.profiles.R
import space.getsub.feature.profiles.add.UserMessage
import space.getsub.feature.profiles.add.toUserMessage
import space.getsub.feature.profiles.add.userMessageText

private val CONTENT_PADDING = 16.dp
private val FIELD_GAP = 12.dp

/**
 * Identifies the loading spinner to instrumented tests — same pattern as
 * [EditorScreen][space.getsub.feature.profiles.editor.EditorScreen]'s own tag.
 */
internal const val SUBSCRIPTION_DETAIL_LOADING_TEST_TAG = "subscription-detail-loading"

/**
 * `:app`'s `SubscriptionDetail(subscriptionId)` route — M3 deliberately left this route
 * undeclared awaiting M4 data (its plan Part 2 line 438); this fills it in.
 *
 * Makes spec D3's precedence visible: a pinned field shows both its own value and the provider
 * value it is declining, never just one ("provider suggests 6 h · you pinned 24 h").
 *
 * [onDone] fires for the back button, the not-found screen's own back button, and once a
 * delete this screen itself started actually completes ([SubscriptionDetailState.deleted]) — the
 * caller (`SubspaceNavHost`) pops the back stack the same way regardless of which of those three
 * fired, exactly like [EditorScreen][space.getsub.feature.profiles.editor.EditorScreen]'s
 * own `onDone`.
 */
@Composable
fun SubscriptionDetailScreen(
    subscriptionId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SubscriptionDetailViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(subscriptionId) { viewModel.load(subscriptionId) }
    LaunchedEffect(state.deleted) { if (state.deleted) onDone() }

    SubscriptionDetailContent(
        state = state,
        actions =
        SubscriptionDetailActions(
            onBack = onDone,
            onRefreshNow = viewModel::onRefreshNow,
            onDismissRefreshResult = viewModel::onDismissRefreshResult,
            onPinRow = viewModel::onPinRow,
            onUnpinRow = viewModel::onUnpinRow,
            onHwidEnabledChanged = viewModel::onHwidEnabledChanged,
            onUserAgentOverrideChanged = viewModel::onUserAgentOverrideChanged,
            onDeleteConfirmed = viewModel::onDelete,
        ),
        modifier = modifier,
    )
}

/**
 * [SubscriptionDetailContent]'s eight callbacks, grouped for the same reason
 * [space.getsub.feature.profiles.editor.EditorActions] is.
 */
internal data class SubscriptionDetailActions(
    val onBack: () -> Unit,
    val onRefreshNow: () -> Unit,
    val onDismissRefreshResult: () -> Unit,
    val onPinRow: (key: String, value: String) -> Unit,
    val onUnpinRow: (key: String) -> Unit,
    val onHwidEnabledChanged: (Boolean) -> Unit,
    val onUserAgentOverrideChanged: (String?) -> Unit,
    val onDeleteConfirmed: () -> Unit,
)

/**
 * The stateless half — see [space.getsub.feature.home.HomeScreenContent]'s KDoc for why
 * this split exists. Exercised directly by `SubscriptionDetailScreenTest`, no Hilt or
 * [SubscriptionDetailViewModel] required.
 */
@Composable
internal fun SubscriptionDetailContent(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SubscriptionDetailTopBar(title = state.groupName, onBack = actions.onBack)
        when {
            state.loading -> LoadingBody()
            state.notFound -> NotFoundBody(onBack = actions.onBack)
            else -> DetailBody(state = state, actions = actions)
        }
    }
}

@Composable
private fun SubscriptionDetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(CONTENT_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.subscription_detail_back_description),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    val loadingDescription = stringResource(R.string.subscription_detail_loading_description)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier =
            Modifier
                .testTag(SUBSCRIPTION_DETAIL_LOADING_TEST_TAG)
                .semantics { contentDescription = loadingDescription },
        )
    }
}

@Composable
private fun NotFoundBody(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(CONTENT_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.subscription_detail_not_found_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.subscription_detail_not_found_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.subscription_detail_not_found_back_button)) }
    }
}

@Composable
private fun DetailBody(
    state: SubscriptionDetailState,
    actions: SubscriptionDetailActions,
    modifier: Modifier = Modifier,
) {
    var deleteRequested by remember { mutableStateOf(false) }

    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CONTENT_PADDING)
            .padding(bottom = CONTENT_PADDING),
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        UrlRow(redactedUrl = state.redactedUrl)
        LastFetchRow(state = state, onRefreshNow = actions.onRefreshNow)
        state.refreshResult?.let { RefreshResultBanner(message = it, onDismiss = actions.onDismissRefreshResult) }
        QuotaBar(usedBytes = state.quotaUsedBytes, totalBytes = state.quotaTotalBytes)
        HwidRow(
            checked = state.hwidEnabled,
            globallyEnabled = state.globalHwidEnabled,
            onCheckedChange = actions.onHwidEnabledChanged,
        )
        UserAgentRow(
            value = state.userAgentOverride,
            providerValue = state.providerUserAgent,
            onSave = actions.onUserAgentOverrideChanged,
        )

        state.rows.forEach { row ->
            DirectiveRowView(row = row, onPin = actions.onPinRow, onUnpin = actions.onUnpinRow)
        }

        DeleteButton(onClick = { deleteRequested = true })
    }

    if (deleteRequested) {
        DeleteSubscriptionDialog(
            name = state.groupName,
            profileCount = state.profileCount,
            onConfirm = {
                deleteRequested = false
                actions.onDeleteConfirmed()
            },
            onDismiss = { deleteRequested = false },
        )
    }
}

@Composable
private fun UrlRow(
    redactedUrl: String,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        icon = Icons.Default.Info,
        label = stringResource(R.string.subscription_detail_url_label),
        supportingText = redactedUrl,
        modifier = modifier,
    )
}

@Composable
private fun LastFetchRow(
    state: SubscriptionDetailState,
    onRefreshNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRow(
        icon = Icons.Default.Refresh,
        label = lastFetchLabel(state.lastFetchState),
        supportingText = lastFetchSupportingText(state.lastFetchState),
        trailing = { RefreshTrailing(isRefreshing = state.isRefreshing, onRefreshNow = onRefreshNow) },
        modifier = modifier,
    )
}

@Composable
private fun RefreshTrailing(
    isRefreshing: Boolean,
    onRefreshNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isRefreshing) {
        val refreshingDescription = stringResource(R.string.subscription_detail_refreshing_description)
        CircularProgressIndicator(
            modifier =
            modifier
                .size(24.dp)
                .semantics { contentDescription = refreshingDescription },
        )
    } else {
        IconButton(onClick = onRefreshNow, modifier = modifier) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = stringResource(R.string.subscription_detail_refresh_action),
            )
        }
    }
}

@Composable
private fun lastFetchLabel(state: LastFetchState): String =
    when (state) {
        LastFetchState.NeverFetched -> stringResource(R.string.subscription_detail_never_fetched)
        is LastFetchState.Succeeded ->
            stringResource(R.string.subscription_detail_last_fetched, relativeTime(state.atEpochMillis))
        is LastFetchState.Failed -> userMessageText(state.reason.toUserMessage())
        is LastFetchState.NoServers -> stringResource(R.string.subscription_error_no_servers)
    }

@Composable
private fun lastFetchSupportingText(state: LastFetchState): String? =
    when (state) {
        is LastFetchState.Failed ->
            state.lastSuccessAtEpochMillis?.let {
                stringResource(R.string.subscription_detail_last_succeeded, relativeTime(it))
            }
                ?: stringResource(R.string.subscription_detail_never_succeeded)
        is LastFetchState.NoServers ->
            state.lastSuccessAtEpochMillis?.let {
                stringResource(R.string.subscription_detail_last_succeeded, relativeTime(it))
            }
                ?: stringResource(R.string.subscription_detail_never_succeeded)
        else -> null
    }

@Composable
private fun relativeTime(epochMillis: Long): String =
    DateUtils
        .getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

/**
 * Renders a [UserMessage] — see
 * [space.getsub.feature.profiles.add.AddServerSheet]'s own `SubscriptionResultText`
 * for the sibling copy this mirrors (private there, not reusable across files).
 */
/**
 * The most recent manual [SubscriptionDetailActions.onRefreshNow] result — tapping it dismisses
 * it. No auto-dismiss timer: a failure reason the user did not get to read defeats the point of
 * showing it, and the next refresh already clears this up front regardless.
 */
@Composable
private fun RefreshResultBanner(
    message: UserMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = userMessageText(message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth().clickable(onClick = onDismiss),
    )
}

@Composable
private fun HwidRow(
    checked: Boolean,
    globallyEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.subscription_detail_hwid_label)
    val description =
        stringResource(
            if (globallyEnabled) {
                R.string.subscription_detail_hwid_description
            } else {
                R.string.subscription_detail_hwid_disabled_globally
            },
        )
    SettingRow(
        icon = Icons.Default.Lock,
        label = label,
        supportingText = description,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = globallyEnabled,
                modifier = Modifier.semantics { contentDescription = label },
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun UserAgentRow(
    value: String?,
    providerValue: String?,
    onSave: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(value) { mutableStateOf(value.orEmpty()) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(stringResource(R.string.subscription_detail_user_agent_label)) },
        supportingText = providerValue?.let { provider ->
            {
                Text(stringResource(R.string.subscription_detail_user_agent_provider, provider))
            }
        },
        placeholder = { Text(stringResource(R.string.subscription_detail_user_agent_placeholder)) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = { onSave(draft.ifBlank { null }) }) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = stringResource(R.string.subscription_detail_user_agent_save),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun DirectiveRowView(
    row: DirectiveRow,
    onPin: (String, String) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(row.labelRes)
    SettingRow(
        icon = Icons.Default.Settings,
        label = label,
        supportingText = directiveSupportingText(row),
        trailing = {
            DirectiveRowTrailing(
                row = row,
                label = label,
                onPin = onPin,
                onUnpin = onUnpin,
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun directiveSupportingText(row: DirectiveRow): String {
    val formattedValue = row.row.value?.let { formatDirectiveValue(row.kind, it) }.orEmpty()
    if (!row.row.showsDeclinedProviderValue) return formattedValue
    val declined = row.row.declinedProviderValue?.let { formatDirectiveValue(row.kind, it) }.orEmpty()
    return stringResource(R.string.subscription_row_pinned_declined, declined, formattedValue)
}

@Composable
private fun formatDirectiveValue(
    kind: DirectiveRowKind,
    raw: String,
): String =
    when (kind) {
        DirectiveRowKind.Hours -> stringResource(R.string.subscription_row_value_hours, raw)
        DirectiveRowKind.Seconds -> stringResource(R.string.subscription_row_value_seconds, raw)
        DirectiveRowKind.Toggle ->
            stringResource(
                if (isDirectiveEnabled(raw)) {
                    R.string.subscription_row_value_on
                } else {
                    R.string.subscription_row_value_off
                },
            )
    }

@Composable
private fun DirectiveRowTrailing(
    row: DirectiveRow,
    label: String,
    onPin: (String, String) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (row.kind) {
        DirectiveRowKind.Toggle ->
            ToggleTrailing(
                row = row,
                label = label,
                onPin = onPin,
                onUnpin = onUnpin,
                modifier = modifier,
            )
        DirectiveRowKind.Hours, DirectiveRowKind.Seconds ->
            NumericTrailing(
                row = row,
                label = label,
                onPin = onPin,
                onUnpin = onUnpin,
                modifier = modifier,
            )
    }
}

@Composable
private fun UnpinButton(
    key: String,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = { onUnpin(key) }, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.subscription_row_unpin_action),
        )
    }
}

@Composable
private fun ToggleTrailing(
    row: DirectiveRow,
    label: String,
    onPin: (String, String) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (row.row.isPinned) UnpinButton(key = row.row.key, onUnpin = onUnpin)
        Switch(
            checked = row.row.isEnabled,
            onCheckedChange = { checked -> onPin(row.row.key, if (checked) "true" else "false") },
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun NumericTrailing(
    row: DirectiveRow,
    label: String,
    onPin: (String, String) -> Unit,
    onUnpin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dialogOpen by remember { mutableStateOf(false) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (row.row.isPinned) UnpinButton(key = row.row.key, onUnpin = onUnpin)
        IconButton(onClick = { dialogOpen = true }) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.subscription_row_edit_description, label),
            )
        }
    }

    if (dialogOpen) {
        PinValueDialog(
            label = label,
            initialValue = row.row.value.orEmpty(),
            onConfirm = { newValue ->
                dialogOpen = false
                onPin(row.row.key, newValue)
            },
            onDismiss = { dialogOpen = false },
        )
    }
}

@Composable
private fun PinValueDialog(
    label: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.subscription_row_pin_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.subscription_row_edit_dialog_cancel)) }
        },
    )
}

@Composable
private fun DeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) {
        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.width(FIELD_GAP))
        Text(text = stringResource(R.string.subscription_detail_delete_action), color = MaterialTheme.colorScheme.error)
    }
}

/** Warns before a subscription is removed — this cascades to its group and every server in it. */
@Composable
private fun DeleteSubscriptionDialog(
    name: String,
    profileCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.subscription_detail_delete_confirm_title, name))
        },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.subscription_detail_delete_confirm_body,
                    profileCount,
                    profileCount,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.subscription_detail_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.subscription_detail_delete_cancel_action))
            }
        },
    )
}
