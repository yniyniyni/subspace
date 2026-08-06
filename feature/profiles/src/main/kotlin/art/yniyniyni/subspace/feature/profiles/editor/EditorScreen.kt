// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the paddings/gaps below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's ConnectControl.kt for the same pattern.
// TooManyFunctions: this file's function count is the width of the form it
// renders (one small composable per field/section, same reasoning
// EditorViewModel.kt's own TooManyFunctions suppression gives for its
// one-setter-per-field surface) — splitting further would not shrink the
// form, only rename how its pieces are grouped into files.
@file:Suppress("MagicNumber", "TooManyFunctions")

package art.yniyniyni.subspace.feature.profiles.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.SHADOWSOCKS_METHODS
import art.yniyniyni.subspace.feature.profiles.R
import art.yniyniyni.subspace.feature.profiles.add.labelRes

private val CONTENT_PADDING = 16.dp
private val FIELD_GAP = 12.dp

/**
 * Identifies the loading spinner to instrumented tests — same pattern as
 * [AddServerSheet][art.yniyniyni.subspace.feature.profiles.add.AddServerSheet]'s own tags.
 */
internal const val EDITOR_LOADING_TEST_TAG = "editor-loading"

/** Identifies the raw-JSON read-only view to instrumented tests. */
internal const val EDITOR_RAW_JSON_TEST_TAG = "editor-raw-json"

/** Identifies the duplicate-identity banner (Task 21 fix round 1) to instrumented tests. */
internal const val EDITOR_DUPLICATE_IDENTITY_TEST_TAG = "editor-duplicate-identity"

private val VMESS_SECURITY_OPTIONS = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")

/**
 * The transports this build can emit a config for — deliberately the same set
 * `StoredProfile.connectable` gates on, minus the aliases (`raw`, `websocket`, `splithttp`),
 * which are the same transports under a second name and would read as duplicates in a
 * dropdown. A profile imported under an alias keeps it; this list is what a user can *pick*.
 *
 * Offering a network the generator cannot emit would let the editor turn a working profile
 * into an unconnectable one, which is the mirror image of the xhttp regression.
 */
private val NETWORK_OPTIONS = listOf("tcp", "ws", "grpc", "xhttp")

/**
 * XHTTP modes, exactly as Xray-core v26.7.11 accepts them (`SplitHTTPConfig.Build`), plus a
 * leading blank for "unset" — which the core reads as `auto`, and which is distinct from
 * choosing `auto` explicitly only in that it writes no key at all.
 *
 * An unrecognised mode is a hard config-build error upstream, not a fallback, so this must
 * stay a closed list rather than a text field.
 */
private val XHTTP_MODES = listOf("", "auto", "packet-up", "stream-up", "stream-one")

private val STREAM_PROTOCOLS = setOf("vless", "vmess", "trojan")

/**
 * `:app`'s `Editor(profileId)` route.
 *
 * A `TYPED` profile gets the full field editor; a `RAW_JSON` one gets a read-only view of its
 * pasted bytes plus rename — see [EditorState]'s own KDoc for why. [onDone] fires once
 * [EditorViewModel.save] actually persists (`state.saved`), or immediately for the back
 * button / not-found screen, so the caller (`SubspaceNavHost`) can pop the back stack either
 * way without this screen knowing anything about navigation itself.
 */
@Composable
fun EditorScreen(
    profileId: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: EditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(profileId) { viewModel.load(profileId) }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    EditorContent(
        state = state,
        actions =
        EditorActions(
            onBack = onDone,
            onNameChanged = viewModel::onNameChanged,
            onGroupChanged = viewModel::onGroupChanged,
            onAddressChanged = viewModel::onAddressChanged,
            onPortChanged = viewModel::onPortChanged,
            onPrimaryCredentialChanged = viewModel::onPrimaryCredentialChanged,
            onSecondaryCredentialChanged = viewModel::onSecondaryCredentialChanged,
            onMethodChanged = viewModel::onMethodChanged,
            onFlowChanged = viewModel::onFlowChanged,
            onAlterIdChanged = viewModel::onAlterIdChanged,
            onVmessSecurityChanged = viewModel::onVmessSecurityChanged,
            onNetworkChanged = viewModel::onNetworkChanged,
            onSecurityKindChanged = viewModel::onSecurityKindChanged,
            onTlsServerNameChanged = viewModel::onTlsServerNameChanged,
            onTlsFingerprintChanged = viewModel::onTlsFingerprintChanged,
            onTlsAllowInsecureChanged = viewModel::onTlsAllowInsecureChanged,
            onRealityServerNameChanged = viewModel::onRealityServerNameChanged,
            onRealityPublicKeyChanged = viewModel::onRealityPublicKeyChanged,
            onRealityShortIdChanged = viewModel::onRealityShortIdChanged,
            onRealityFingerprintChanged = viewModel::onRealityFingerprintChanged,
            onRealitySpiderXChanged = viewModel::onRealitySpiderXChanged,
            onWsPathChanged = viewModel::onWsPathChanged,
            onGrpcServiceNameChanged = viewModel::onGrpcServiceNameChanged,
            onXhttpPathChanged = viewModel::onXhttpPathChanged,
            onXhttpHostChanged = viewModel::onXhttpHostChanged,
            onXhttpModeChanged = viewModel::onXhttpModeChanged,
            onSave = viewModel::save,
        ),
        modifier = modifier,
    )
}

/**
 * [EditorContent]'s callbacks, grouped for the same reason
 * [art.yniyniyni.subspace.feature.profiles.list.ServersActions] is — a data class, so
 * detekt's `LongParameterList` (which exempts data class constructors) does not fire the way
 * it would on a composable with this many parameters.
 */
internal data class EditorActions(
    val onBack: () -> Unit,
    val onNameChanged: (String) -> Unit,
    val onGroupChanged: (Long) -> Unit,
    val onAddressChanged: (String) -> Unit,
    val onPortChanged: (String) -> Unit,
    val onPrimaryCredentialChanged: (String) -> Unit,
    val onSecondaryCredentialChanged: (String) -> Unit,
    val onMethodChanged: (String) -> Unit,
    val onFlowChanged: (String) -> Unit,
    val onAlterIdChanged: (String) -> Unit,
    val onVmessSecurityChanged: (String) -> Unit,
    val onNetworkChanged: (String) -> Unit,
    val onSecurityKindChanged: (EditorSecurityKind) -> Unit,
    val onTlsServerNameChanged: (String) -> Unit,
    val onTlsFingerprintChanged: (String) -> Unit,
    val onTlsAllowInsecureChanged: (Boolean) -> Unit,
    val onRealityServerNameChanged: (String) -> Unit,
    val onRealityPublicKeyChanged: (String) -> Unit,
    val onRealityShortIdChanged: (String) -> Unit,
    val onRealityFingerprintChanged: (String) -> Unit,
    val onRealitySpiderXChanged: (String) -> Unit,
    val onWsPathChanged: (String) -> Unit,
    val onGrpcServiceNameChanged: (String) -> Unit,
    val onXhttpPathChanged: (String) -> Unit,
    val onXhttpHostChanged: (String) -> Unit,
    val onXhttpModeChanged: (String) -> Unit,
    val onSave: () -> Unit,
)

/**
 * The stateless half — see [art.yniyniyni.subspace.feature.home.HomeScreenContent]'s KDoc for
 * why this split exists. Exercised directly by [EditorScreenTest], no Hilt or [EditorViewModel]
 * required.
 */
@Composable
internal fun EditorContent(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(onBack = actions.onBack)
        when {
            state.loading -> LoadingBody()
            !state.exists -> NotFoundBody(onBack = actions.onBack)
            else -> EditorForm(state = state, actions = actions)
        }
    }
}

@Composable
private fun EditorTopBar(
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
                contentDescription = stringResource(R.string.editor_back_description),
            )
        }
        Text(text = stringResource(R.string.editor_title), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LoadingBody(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier =
            Modifier
                .testTag(EDITOR_LOADING_TEST_TAG)
                .semantics { contentDescription = "" },
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
        Text(text = stringResource(R.string.editor_not_found_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.editor_not_found_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onBack) { Text(stringResource(R.string.editor_not_found_back_button)) }
    }
}

@Composable
private fun EditorForm(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CONTENT_PADDING)
            .padding(bottom = CONTENT_PADDING),
        verticalArrangement = Arrangement.spacedBy(FIELD_GAP),
    ) {
        EditorField(
            label = stringResource(R.string.editor_name_label),
            value = state.name,
            onValueChange = actions.onNameChanged,
        )

        if (state.fieldsEditable) {
            TypedFields(state = state, actions = actions)
        } else {
            RawJsonFields(state = state)
        }

        if (state.duplicateIdentity) {
            Text(
                text = stringResource(R.string.editor_duplicate_identity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(EDITOR_DUPLICATE_IDENTITY_TEST_TAG),
            )
        }

        Button(onClick = actions.onSave, modifier = Modifier.padding(top = FIELD_GAP)) {
            Text(stringResource(R.string.editor_save_button))
        }
    }
}

@Composable
private fun TypedFields(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        GroupDropdown(
            selectedId = state.groupId,
            options = state.availableGroups,
            onSelected = actions.onGroupChanged,
        )
        EditorField(
            label = stringResource(R.string.editor_address_label),
            value = state.address,
            onValueChange = actions.onAddressChanged,
        )
        EditorField(
            label = stringResource(R.string.editor_port_label),
            value = state.port,
            onValueChange = actions.onPortChanged,
            keyboardType = KeyboardType.Number,
            error = state.errors[EditorFieldKey.Port],
        )

        CredentialFields(state = state, actions = actions)

        if (state.protocol in STREAM_PROTOCOLS) {
            TransportSection(state = state, actions = actions)
            SecuritySection(state = state, actions = actions)
        }
    }
}

// Five protocols, five different credential shapes (§ARCHITECTURE 6: "credential" means UUID
// for vless/vmess, password for trojan/shadowsocks, username+password for socks) — the
// length here is the width of that dispatch, not a candidate for further splitting without
// just renaming the same branches into five one-line functions.
@Suppress("LongMethod")
@Composable
private fun CredentialFields(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        when (state.protocol) {
            "vless" -> {
                EditorField(
                    label = stringResource(R.string.editor_uuid_label),
                    value = state.primaryCredential,
                    onValueChange = actions.onPrimaryCredentialChanged,
                    error = state.errors[EditorFieldKey.Credential],
                )
                EditorField(
                    label = stringResource(R.string.editor_flow_label),
                    value = state.flow,
                    onValueChange = actions.onFlowChanged,
                )
            }
            "vmess" -> {
                EditorField(
                    label = stringResource(R.string.editor_uuid_label),
                    value = state.primaryCredential,
                    onValueChange = actions.onPrimaryCredentialChanged,
                    error = state.errors[EditorFieldKey.Credential],
                )
                EditorField(
                    label = stringResource(R.string.editor_alter_id_label),
                    value = state.alterId,
                    onValueChange = actions.onAlterIdChanged,
                    keyboardType = KeyboardType.Number,
                )
                EditorDropdownField(
                    label = stringResource(R.string.editor_vmess_security_label),
                    selected = state.vmessSecurity,
                    options = VMESS_SECURITY_OPTIONS,
                    onSelected = actions.onVmessSecurityChanged,
                )
            }
            "trojan" ->
                EditorField(
                    label = stringResource(R.string.editor_password_label),
                    value = state.primaryCredential,
                    onValueChange = actions.onPrimaryCredentialChanged,
                    error = state.errors[EditorFieldKey.Credential],
                )
            "shadowsocks" -> {
                EditorDropdownField(
                    label = stringResource(R.string.editor_method_label),
                    selected = state.method,
                    options = SHADOWSOCKS_METHODS.toList(),
                    onSelected = actions.onMethodChanged,
                    error = state.errors[EditorFieldKey.Method],
                )
                EditorField(
                    label = stringResource(R.string.editor_password_label),
                    value = state.primaryCredential,
                    onValueChange = actions.onPrimaryCredentialChanged,
                    error = state.errors[EditorFieldKey.Credential],
                )
            }
            else -> {
                EditorField(
                    label = stringResource(R.string.editor_username_label),
                    value = state.primaryCredential,
                    onValueChange = actions.onPrimaryCredentialChanged,
                )
                EditorField(
                    label = stringResource(R.string.editor_socks_password_label),
                    value = state.secondaryCredential,
                    onValueChange = actions.onSecondaryCredentialChanged,
                )
            }
        }
    }
}

@Composable
private fun TransportSection(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        EditorDropdownField(
            label = stringResource(R.string.editor_network_label),
            selected = state.network,
            options = NETWORK_OPTIONS,
            onSelected = actions.onNetworkChanged,
        )
        when (state.network) {
            "ws" ->
                EditorField(
                    label = stringResource(R.string.editor_ws_path_label),
                    value = state.wsPath,
                    onValueChange = actions.onWsPathChanged,
                )
            "grpc" ->
                EditorField(
                    label = stringResource(R.string.editor_grpc_service_name_label),
                    value = state.grpcServiceName,
                    onValueChange = actions.onGrpcServiceNameChanged,
                )

            // Both spellings: a profile imported from a config written against the
            // older name keeps it, and its fields must still be editable.
            "xhttp", "splithttp" -> XhttpFields(state, actions)
        }
    }
}

@Composable
private fun XhttpFields(
    state: EditorState,
    actions: EditorActions,
) {
    EditorField(
        label = stringResource(R.string.editor_xhttp_path_label),
        value = state.xhttpPath,
        onValueChange = actions.onXhttpPathChanged,
    )
    EditorField(
        label = stringResource(R.string.editor_xhttp_host_label),
        value = state.xhttpHost,
        onValueChange = actions.onXhttpHostChanged,
    )
    EditorDropdownField(
        label = stringResource(R.string.editor_xhttp_mode_label),
        selected = state.xhttpMode,
        options = XHTTP_MODES,
        onSelected = actions.onXhttpModeChanged,
        // The blank sentinel needs a readable label; the four real modes are
        // Xray's own literals and are not translated (they go into the config).
        optionLabel = { mode -> mode.ifEmpty { stringResource(R.string.editor_xhttp_mode_auto) } },
    )
}

@Composable
private fun SecuritySection(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    // Resolved to display strings once, here in composable context — displayLabel() is
    // @Composable (it reads a string resource for EditorSecurityKind.None), so it cannot be
    // called from inside the plain onSelected closure below.
    val labelsByKind = EditorSecurityKind.entries.associateWith { it.displayLabel() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        EditorDropdownField(
            label = stringResource(R.string.editor_security_label),
            selected = labelsByKind.getValue(state.securityKind),
            options = labelsByKind.values.toList(),
            onSelected = { label ->
                actions.onSecurityKindChanged(labelsByKind.entries.first { it.value == label }.key)
            },
        )
        when (state.securityKind) {
            EditorSecurityKind.None -> Unit
            EditorSecurityKind.Tls -> TlsFields(state = state, actions = actions)
            EditorSecurityKind.Reality -> RealityFields(state = state, actions = actions)
        }
    }
}

@Composable
private fun EditorSecurityKind.displayLabel(): String =
    when (this) {
        // "Reality"/"TLS" are Xray's own technical transport names, not sentences — same
        // un-resourced treatment ServersViewModel.toProtocolDisplayName() gives "VLESS"/"VMess".
        EditorSecurityKind.None -> stringResource(R.string.editor_security_none)
        EditorSecurityKind.Reality -> "Reality"
        EditorSecurityKind.Tls -> "TLS"
    }

@Composable
private fun TlsFields(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        EditorField(
            label = stringResource(R.string.editor_tls_server_name_label),
            value = state.tlsServerName,
            onValueChange = actions.onTlsServerNameChanged,
        )
        EditorField(
            label = stringResource(R.string.editor_tls_fingerprint_label),
            value = state.tlsFingerprint,
            onValueChange = actions.onTlsFingerprintChanged,
        )
        EditorSwitchField(
            label = stringResource(R.string.editor_tls_allow_insecure_label),
            checked = state.tlsAllowInsecure,
            onCheckedChange = actions.onTlsAllowInsecureChanged,
        )
    }
}

@Composable
private fun RealityFields(
    state: EditorState,
    actions: EditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        EditorField(
            label = stringResource(R.string.editor_reality_server_name_label),
            value = state.realityServerName,
            onValueChange = actions.onRealityServerNameChanged,
        )
        EditorField(
            label = stringResource(R.string.editor_reality_public_key_label),
            value = state.realityPublicKey,
            onValueChange = actions.onRealityPublicKeyChanged,
            error = state.errors[EditorFieldKey.PublicKey],
        )
        EditorField(
            label = stringResource(R.string.editor_reality_short_id_label),
            value = state.realityShortId,
            onValueChange = actions.onRealityShortIdChanged,
        )
        EditorField(
            label = stringResource(R.string.editor_reality_fingerprint_label),
            value = state.realityFingerprint,
            onValueChange = actions.onRealityFingerprintChanged,
        )
        EditorField(
            label = stringResource(R.string.editor_reality_spider_x_label),
            value = state.realitySpiderX,
            onValueChange = actions.onRealitySpiderXChanged,
        )
    }
}

/**
 * The RAW_JSON half of [EditorForm] — protocol/address/port as plain (non-editable) text plus
 * the pasted bytes verbatim, never a form. See [EditorState]'s own KDoc for why re-serializing
 * this JSON through a form is exactly what this build must not do.
 */
@Composable
private fun RawJsonFields(
    state: EditorState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(FIELD_GAP)) {
        Text(
            text = stringResource(R.string.editor_raw_json_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = stringResource(R.string.editor_raw_json_label), style = MaterialTheme.typography.labelLarge)
        Text(
            text = state.rawJson.orEmpty(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier =
            Modifier
                .fillMaxWidth()
                .testTag(EDITOR_RAW_JSON_TEST_TAG),
        )
    }
}

// One shared text-field primitive, reused for every field in the form —
// label/value/callback/modifier are the composable-parameter baseline every
// field in this module carries, keyboardType and error are the two axes an
// individual field opts into (numeric input, validation). Grouping these into
// a carrier class the way ImportActions/ServersActions do would just move the
// same six names one level down without shrinking anything.
@Suppress("LongParameterList")
@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: FailureDetail? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(editorErrorText(label, it)) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorSwitchField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

// Same reasoning as EditorField's own suppression just above.
@Suppress("LongParameterList")
@Composable
private fun EditorDropdownField(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: FailureDetail? = null,
    // Defaults to the option itself, which is right for every existing caller: their
    // options are Xray's own literals and go into the config verbatim. Overridden only
    // where an option has no readable form of its own — XHTTP's blank "unset" sentinel,
    // which would otherwise render as an empty, unlabelled menu row with nothing for a
    // screen reader to announce.
    optionLabel: @Composable (String) -> String = { it },
) {
    var menuOpen by remember { mutableStateOf(false) }
    val labelledValue = stringResource(R.string.editor_dropdown_value, label, optionLabel(selected))

    Column(modifier = modifier) {
        Box {
            TextButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = labelledValue },
            ) {
                Text(labelledValue)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                options.forEach { option ->
                    val optionText = optionLabel(option)
                    DropdownMenuItem(
                        text = { Text(optionText) },
                        onClick = {
                            menuOpen = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
        if (error != null) {
            Text(
                text = editorErrorText(label, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GroupDropdown(
    selectedId: Long,
    options: List<EditorGroupOption>,
    onSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedName = options.firstOrNull { it.id == selectedId }?.name.orEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    val label = stringResource(R.string.editor_group_label)
    val labelledValue = stringResource(R.string.editor_dropdown_value, label, selectedName)

    Box(modifier = modifier) {
        TextButton(
            onClick = { menuOpen = true },
            modifier = Modifier.semantics { contentDescription = labelledValue },
        ) {
            Text(labelledValue)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    onClick = {
                        menuOpen = false
                        onSelected(option.id)
                    },
                )
            }
        }
    }
}

/**
 * A single-field counterpart to [art.yniyniyni.subspace.feature.profiles.add.failureText] —
 * see [DetailField.labelRes]'s own KDoc for why the field-name vocabulary is shared rather
 * than duplicated a second time in this module. No "Entry N" prefix: this is one field on a
 * form, not one line of a parsed list.
 */
@Composable
private fun editorErrorText(
    fieldLabel: String,
    detail: FailureDetail,
): String =
    when (detail) {
        FailureDetail.None -> fieldLabel
        is FailureDetail.Length ->
            stringResource(
                R.string.editor_error_length,
                stringResource(detail.field.labelRes()),
                detail.expected,
                detail.actual,
            )
        is FailureDetail.Range ->
            stringResource(
                R.string.editor_error_range,
                stringResource(detail.field.labelRes()),
                detail.min,
                detail.max,
                detail.actual,
            )
        is FailureDetail.Missing ->
            stringResource(R.string.editor_error_missing, stringResource(detail.field.labelRes()))
        is FailureDetail.Unsupported ->
            stringResource(R.string.editor_error_unsupported, stringResource(detail.field.labelRes()))
        is FailureDetail.Malformed ->
            stringResource(R.string.editor_error_malformed, stringResource(detail.field.labelRes()))
    }
