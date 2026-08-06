// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.StreamSettings
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.validatePort
import art.yniyniyni.subspace.core.parser.validateRealityPublicKey
import art.yniyniyni.subspace.core.parser.validateShadowsocksMethod
import art.yniyniyni.subspace.core.parser.validateUuid
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One group the group picker can move a TYPED profile into. */
internal data class EditorGroupOption(val id: Long, val name: String)

/** Which of the four field-level validators (§ARCHITECTURE 6) currently rejects a TYPED profile's draft. */
internal enum class EditorFieldKey { Port, Credential, PublicKey, Method }

/** `StreamSettings.security`'s three shapes, as something a dropdown can select without nested data. */
internal enum class EditorSecurityKind { None, Reality, Tls }

/**
 * What [EditorScreen] renders.
 *
 * ARCHITECTURE.md §6: a [ProfileKind.RAW_JSON] profile is stored byte-for-byte and runs
 * through the typed projection, not the pasted bytes (passthrough execution is not
 * implemented — see [StoredProfile.compatibilityMode]'s own KDoc). Letting the editor turn
 * that JSON into a form and re-serialize it on save is exactly the lossy round trip §6 exists
 * to prevent: unmodelled fields (fragmentation, custom headers, anything this app's
 * [Outbound] has no property for) would silently vanish the moment a user hit Save. So
 * [fieldsEditable] is `false` for [ProfileKind.RAW_JSON] and stays `true` only for
 * [ProfileKind.TYPED] — [rawJson] is shown read-only, and the one write [EditorViewModel.save]
 * performs for a RAW_JSON profile is a rename, never a reconstruction of the JSON.
 *
 * Every editable field below is a plain [String] (or the narrow [EditorSecurityKind]/
 * [EditorGroupOption] enums), not the typed [Outbound] value it will become — the same
 * reason [art.yniyniyni.subspace.feature.profiles.add.ImportState.input] is a raw string,
 * not a parsed profile: a text field needs something it can hold mid-edit (an empty port
 * field, a half-typed UUID), which a strongly-typed [Outbound] cannot represent. [errors]
 * is populated from [art.yniyniyni.subspace.core.parser]'s own validators on [save] — the
 * same functions [art.yniyniyni.subspace.core.parser.SubscriptionParser] uses on import, so
 * the editor and the importer cannot disagree about what is valid.
 *
 * Deliberately not exposed here: WebSocket header editing ([TransportOptions.WebSocket.headers]
 * is a `Map<String, String>`, not a single field a text box can drive) — see [toOutbound]'s
 * KDoc for how those survive a save unedited rather than being dropped.
 */
internal data class EditorState(
    val loading: Boolean = true,
    /** `false` only once [EditorViewModel.load] resolves a `null` profile — an id that names no real row. */
    val exists: Boolean = true,
    val id: Long = 0L,
    val kind: ProfileKind = ProfileKind.TYPED,
    val protocol: String = "",
    val name: String = "",
    val groupId: Long = 0L,
    /**
     * [groupId] as it was when [EditorViewModel.load] resolved this profile, kept alongside
     * the (possibly since edited) [groupId] so [EditorViewModel.save] can tell "the user
     * changed the group" from "the group was always this" without a second round trip to
     * [art.yniyniyni.subspace.feature.profiles.ProfileSource.profile]. Never itself editable.
     */
    val loadedGroupId: Long = 0L,
    val availableGroups: List<EditorGroupOption> = emptyList(),
    /** Non-null only for [ProfileKind.RAW_JSON] — the exact pasted bytes, shown read-only. */
    val rawJson: String? = null,
    val address: String = "",
    val port: String = "",
    /** UUID (vless/vmess), password (trojan/shadowsocks), or username (socks — see [secondaryCredential]). */
    val primaryCredential: String = "",
    /** SOCKS password only; every other protocol leaves this blank and unused. */
    val secondaryCredential: String = "",
    /** Shadowsocks cipher, one of [art.yniyniyni.subspace.core.parser.SHADOWSOCKS_METHODS]. */
    val method: String = "",
    /** VLESS XTLS flow, e.g. `xtls-rprx-vision`. Blank means unset. */
    val flow: String = "",
    /** VMess legacy AlterID, as text — 0 on every modern server. */
    val alterId: String = "",
    /** VMess encryption: `auto`, `aes-128-gcm`, `chacha20-poly1305`, `none`. */
    val vmessSecurity: String = "auto",
    /** `tcp`, `ws` or `grpc` — vless/vmess/trojan only; shadowsocks/socks carry no [StreamSettings]. */
    val network: String = "tcp",
    val securityKind: EditorSecurityKind = EditorSecurityKind.None,
    val tlsServerName: String = "",
    val tlsFingerprint: String = "",
    val tlsAllowInsecure: Boolean = false,
    val realityServerName: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realityFingerprint: String = "",
    val realitySpiderX: String = "",
    val wsPath: String = "",
    val grpcServiceName: String = "",
    val xhttpPath: String = "",
    /** XHTTP `Host` header. Blank means unset, which dials with the address (§ see [toOutbound]). */
    val xhttpHost: String = "",
    /**
     * XHTTP mode, one of [XHTTP_MODES]. Blank means unset, which Xray reads as `auto`.
     *
     * A closed option list rather than a text field on purpose: Xray-core v26.7.11 rejects
     * the whole config on an unrecognised mode (`SplitHTTPConfig.Build`: `unsupported mode`),
     * so a typo here would turn a working profile into one that fails at validation.
     */
    val xhttpMode: String = "",
    /** Preserved from the loaded outbound, unedited — see [toOutbound]'s KDoc. */
    val originalWsHeaders: Map<String, String> = emptyMap(),
    val errors: Map<EditorFieldKey, FailureDetail> = emptyMap(),
    /**
     * `true` when the last [EditorViewModel.save] was rejected because the edited outbound
     * (or the destination group, for a group change) already holds an identical profile —
     * [art.yniyniyni.subspace.core.data.db.ProfileEntity]'s unique `(groupId, identityHash)`
     * index (§4.2). Not a [FailureDetail] like [errors]: this is not one field being wrong,
     * it is the whole draft colliding with a sibling row, so it renders as a single banner
     * (see [EditorScreen]) rather than attaching to any one text field. Reset to `false` at
     * the start of every [EditorViewModel.save] call, so it reflects only the most recent
     * attempt.
     */
    val duplicateIdentity: Boolean = false,
    val saved: Boolean = false,
) {
    /** RAW_JSON is read-only plus rename (§6) — see this file's own KDoc for why. */
    val fieldsEditable: Boolean get() = kind == ProfileKind.TYPED
}

/**
 * Loads one [StoredProfile] for editing and writes back through [ProfileSource].
 *
 * [load] must be called once (from `LaunchedEffect(profileId)`, see [EditorScreen]) before
 * [state] carries anything but its initial [EditorState.loading] value.
 */
@Suppress("TooManyFunctions") // One onXChanged per editable field (§ARCHITECTURE 6's "name, group, address,
// port, credential, transport, security" plus each protocol's own sub-fields) — the width of
// this class's surface is the width of the form it drives, same call ProfileRepository.kt's
// own KDoc makes for its own suppression.
@HiltViewModel
internal class EditorViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    fun load(profileId: Long) {
        viewModelScope.launch {
            val groups =
                profileSource
                    .observeGroups(query = "", protocol = null)
                    .first()
                    .map { EditorGroupOption(it.id, it.name) }
            val profile = profileSource.profile(profileId)
            _state.value =
                if (profile == null) {
                    EditorState(loading = false, exists = false, id = profileId, availableGroups = groups)
                } else {
                    profile.toEditorState(groups)
                }
        }
    }

    fun onNameChanged(value: String) = update { it.copy(name = value) }

    fun onGroupChanged(id: Long) = update { it.copy(groupId = id) }

    fun onAddressChanged(value: String) = update { it.copy(address = value) }

    fun onPortChanged(value: String) = update { it.copy(port = value) }

    fun onPrimaryCredentialChanged(value: String) = update { it.copy(primaryCredential = value) }

    fun onSecondaryCredentialChanged(value: String) = update { it.copy(secondaryCredential = value) }

    fun onMethodChanged(value: String) = update { it.copy(method = value) }

    fun onFlowChanged(value: String) = update { it.copy(flow = value) }

    fun onAlterIdChanged(value: String) = update { it.copy(alterId = value) }

    fun onVmessSecurityChanged(value: String) = update { it.copy(vmessSecurity = value) }

    fun onNetworkChanged(value: String) = update { it.copy(network = value) }

    fun onSecurityKindChanged(value: EditorSecurityKind) = update { it.copy(securityKind = value) }

    fun onTlsServerNameChanged(value: String) = update { it.copy(tlsServerName = value) }

    fun onTlsFingerprintChanged(value: String) = update { it.copy(tlsFingerprint = value) }

    fun onTlsAllowInsecureChanged(value: Boolean) = update { it.copy(tlsAllowInsecure = value) }

    fun onRealityServerNameChanged(value: String) = update { it.copy(realityServerName = value) }

    fun onRealityPublicKeyChanged(value: String) = update { it.copy(realityPublicKey = value) }

    fun onRealityShortIdChanged(value: String) = update { it.copy(realityShortId = value) }

    fun onRealityFingerprintChanged(value: String) = update { it.copy(realityFingerprint = value) }

    fun onRealitySpiderXChanged(value: String) = update { it.copy(realitySpiderX = value) }

    fun onWsPathChanged(value: String) = update { it.copy(wsPath = value) }

    fun onGrpcServiceNameChanged(value: String) = update { it.copy(grpcServiceName = value) }

    fun onXhttpPathChanged(value: String) = update { it.copy(xhttpPath = value) }

    fun onXhttpHostChanged(value: String) = update { it.copy(xhttpHost = value) }

    fun onXhttpModeChanged(value: String) = update { it.copy(xhttpMode = value) }

    /**
     * Persists the current draft.
     *
     * A RAW_JSON profile calls only [ProfileSource.rename] — its bytes are never touched
     * (§6). A TYPED profile validates first: any [EditorFieldKey] failure aborts the write
     * and populates [EditorState.errors] instead, the same "validate before starting" the
     * importer already follows. A group change is a separate [ProfileSource.move] call —
     * [ProfileSource.update] does not touch `groupId`.
     *
     * Task 21 fix round 1: [ProfileSource.move] and [ProfileSource.update] both return
     * `false` instead of throwing when the edited profile now collides with a sibling under
     * [art.yniyniyni.subspace.core.data.db.ProfileEntity]'s unique `(groupId, identityHash)`
     * index — see [ProfileRepository.update][art.yniyniyni.subspace.core.data.ProfileRepository.update]'s
     * KDoc. Either `false` aborts the save, sets [EditorState.duplicateIdentity] and leaves
     * the draft exactly as the user left it (nothing here resets any field), matching the
     * validation-failure branch just above it: a rejected save is a state the user can act
     * on, never an uncaught exception reaching [viewModelScope] (ARCHITECTURE.md §10.4).
     */
    fun save() {
        val current = _state.value
        if (!current.exists) return
        viewModelScope.launch {
            _state.update { it.copy(duplicateIdentity = false) }
            if (current.fieldsEditable) {
                val validationErrors = current.validate()
                if (validationErrors.isNotEmpty()) {
                    _state.update { it.copy(errors = validationErrors) }
                    return@launch
                }
                if (current.groupId != current.loadedGroupId) {
                    if (!profileSource.move(current.id, current.groupId)) {
                        _state.update { it.copy(duplicateIdentity = true) }
                        return@launch
                    }
                }
                if (!profileSource.update(current.id, current.name, current.toOutbound())) {
                    _state.update { it.copy(duplicateIdentity = true) }
                    return@launch
                }
            } else {
                profileSource.rename(current.id, current.name)
            }
            _state.update { it.copy(errors = emptyMap(), saved = true) }
        }
    }

    private inline fun update(transform: (EditorState) -> EditorState) {
        _state.update(transform)
    }
}

/** Populates [EditorState] from a freshly loaded [StoredProfile]. */
private fun StoredProfile.toEditorState(groups: List<EditorGroupOption>): EditorState {
    val base =
        EditorState(
            loading = false,
            exists = true,
            id = id,
            kind = kind,
            protocol = protocol,
            name = name,
            groupId = groupId,
            loadedGroupId = groupId,
            availableGroups = groups,
            rawJson = rawJson,
            address = address,
            port = port.toString(),
        )
    val ob = outbound ?: return base
    return when (ob) {
        is VlessOutbound ->
            base
                .copy(primaryCredential = ob.uuid, flow = ob.flow.orEmpty())
                .withStream(ob.stream)
        is VmessOutbound ->
            base
                .copy(primaryCredential = ob.uuid, alterId = ob.alterId.toString(), vmessSecurity = ob.security)
                .withStream(ob.stream)
        is TrojanOutbound -> base.copy(primaryCredential = ob.password).withStream(ob.stream)
        is ShadowsocksOutbound -> base.copy(method = ob.method, primaryCredential = ob.password)
        is SocksOutbound ->
            base.copy(primaryCredential = ob.username.orEmpty(), secondaryCredential = ob.password.orEmpty())
    }
}

private fun EditorState.withStream(stream: StreamSettings): EditorState {
    val withSecurity =
        when (val security = stream.security) {
            Security.None -> copy(securityKind = EditorSecurityKind.None)
            is Security.Tls ->
                copy(
                    securityKind = EditorSecurityKind.Tls,
                    tlsServerName = security.serverName,
                    tlsFingerprint = security.fingerprint,
                    tlsAllowInsecure = security.allowInsecure,
                )
            is Security.Reality ->
                copy(
                    securityKind = EditorSecurityKind.Reality,
                    realityServerName = security.serverName,
                    realityPublicKey = security.publicKey,
                    realityShortId = security.shortId,
                    realityFingerprint = security.fingerprint,
                    realitySpiderX = security.spiderX,
                )
        }
    return when (val transport = stream.transport) {
        TransportOptions.None -> withSecurity.copy(network = stream.network)
        is TransportOptions.WebSocket ->
            withSecurity.copy(network = stream.network, wsPath = transport.path, originalWsHeaders = transport.headers)
        is TransportOptions.Grpc ->
            withSecurity.copy(network = stream.network, grpcServiceName = transport.serviceName)
        is TransportOptions.Xhttp ->
            withSecurity.copy(
                network = stream.network,
                xhttpPath = transport.path,
                // Null becomes blank for the text field and blank becomes null again in
                // [toOutbound], so an unset host survives an untouched edit as unset rather
                // than being written back as an empty header.
                xhttpHost = transport.host.orEmpty(),
                xhttpMode = transport.mode.orEmpty(),
            )
    }
}

/**
 * Every field-level check §6 requires before a TYPED profile can be saved, run through
 * `:core:parser`'s own validators — see [EditorViewModel.save]'s KDoc for why this must not
 * be a second, independently-drifting copy of what the importer already enforces.
 */
private fun EditorState.validate(): Map<EditorFieldKey, FailureDetail> {
    val errors = mutableMapOf<EditorFieldKey, FailureDetail>()

    val portValue = port.toIntOrNull()
    val portError = if (portValue == null) FailureDetail.Malformed(DetailField.Port) else validatePort(portValue)
    portError?.let { errors[EditorFieldKey.Port] = it }

    when (protocol) {
        "vless", "vmess" -> validateUuid(primaryCredential)?.let { errors[EditorFieldKey.Credential] = it }
        "trojan", "shadowsocks" ->
            if (primaryCredential.isEmpty()) {
                errors[EditorFieldKey.Credential] = FailureDetail.Missing(DetailField.Password)
            }
        // socks: both credentials are genuinely optional (SocksOutbound.username/password are nullable).
    }
    if (protocol == "shadowsocks") {
        validateShadowsocksMethod(method)?.let { errors[EditorFieldKey.Method] = it }
    }
    if (securityKind == EditorSecurityKind.Reality) {
        validateRealityPublicKey(realityPublicKey)?.let { errors[EditorFieldKey.PublicKey] = it }
    }
    return errors
}

/**
 * Reconstructs the [Outbound] [EditorViewModel.save] persists — only called after
 * [EditorState.validate] returned no errors, so `port.toIntOrNull()`/etc. below are safe.
 *
 * [EditorState.originalWsHeaders] passes through untouched: this editor exposes a WebSocket
 * transport's path but not its header map (a `Map<String, String>` has no single-field form
 * this task builds a UI for), so a save must carry the loaded headers forward rather than
 * silently dropping them to an empty map — the exact "unmodelled fields vanish" failure §6
 * exists to prevent, this time for a TYPED profile instead of RAW_JSON.
 *
 * The `xhttp` branch is the same guarantee for the transport that regression exposed: before
 * it existed, saving an xhttp profile through this editor rewrote its transport to
 * [TransportOptions.None], discarding the path and Host it was imported with. Editing the
 * name of a working server must not quietly change how it dials.
 */
private fun EditorState.toTransportOptions(): TransportOptions =
    when (network) {
        "ws" -> TransportOptions.WebSocket(path = wsPath, headers = originalWsHeaders)
        "grpc" -> TransportOptions.Grpc(serviceName = grpcServiceName)
        // Blank back to null, the inverse of [withStream]'s orEmpty(): "" and null are
        // different requests on the wire, and only null means "let Xray decide".
        "xhttp", "splithttp" ->
            TransportOptions.Xhttp(
                path = xhttpPath.ifBlank { "/" },
                host = xhttpHost.ifBlank { null },
                mode = xhttpMode.ifBlank { null },
            )

        else -> TransportOptions.None
    }

private fun EditorState.toOutbound(): Outbound {
    val portValue = port.toIntOrNull() ?: 0
    val security: Security =
        when (securityKind) {
            EditorSecurityKind.None -> Security.None
            EditorSecurityKind.Reality ->
                Security.Reality(
                    realityServerName,
                    realityPublicKey,
                    realityShortId,
                    realityFingerprint,
                    realitySpiderX,
                )
            EditorSecurityKind.Tls -> Security.Tls(tlsServerName, tlsFingerprint, tlsAllowInsecure)
        }
    val stream = StreamSettings(network = network, security = security, transport = toTransportOptions())
    return when (protocol) {
        "vless" -> VlessOutbound(address, portValue, primaryCredential, flow.ifBlank { null }, stream)
        "vmess" ->
            VmessOutbound(address, portValue, primaryCredential, alterId.toIntOrNull() ?: 0, vmessSecurity, stream)
        "trojan" -> TrojanOutbound(address, portValue, primaryCredential, stream)
        "shadowsocks" -> ShadowsocksOutbound(address, portValue, method, primaryCredential)
        else ->
            SocksOutbound(address, portValue, primaryCredential.ifBlank { null }, secondaryCredential.ifBlank { null })
    }
}
