// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.xray.ShareLinkConverter
import art.yniyniyni.subspace.core.xray.XrayException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the screen renders. */
internal data class HomeState(
    val input: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Null when there is nothing to complain about. A string resource id. */
    val inputError: Int? = null,
    val busy: Boolean = false,
) {
    val canConnect: Boolean
        get() = input.isNotBlank() && connection is ConnectionState.Disconnected && !busy

    val canDisconnect: Boolean
        get() =
            connection is ConnectionState.Connected ||
                connection is ConnectionState.Connecting
}

@HiltViewModel
internal class HomeViewModel @Inject constructor(
    private val tunnel: TunnelConnection,
) : ViewModel() {
    private val input = MutableStateFlow("")
    private val inputError = MutableStateFlow<Int?>(null)
    private val busy = MutableStateFlow(false)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        // §5.5: the connection half of this state comes from the service and
        // is only ever mirrored here. The text field is the only part the UI
        // actually owns.
        combine(input, tunnel.state, inputError, busy) { text, conn, error, working ->
            HomeState(input = text, connection = conn, inputError = error, busy = working)
        }.onEach { _state.value = it }
            .launchIn(viewModelScope)
    }

    fun onInputChanged(value: String) {
        input.value = value
        inputError.value = null
    }

    /**
     * Turns whatever was pasted into a [Profile].
     *
     * Accepts a `vless://` link, or a raw Xray config — the latter converted
     * by the core itself (see [ShareLinkConverter]), because a config the
     * core already understands is a better source of truth than a second
     * parser of ours.
     *
     * @return null if nothing usable came out; [inputError] then holds the
     *   reason.
     */
    @Suppress("ReturnCount")
    private suspend fun parseInput(raw: String): Profile? {
        val text = raw.trim()

        VlessLinkParser.parse(text)?.let { return it }

        if (!text.startsWith("{")) {
            inputError.value = R.string.error_unparseable
            return null
        }

        // §5.3: conversion goes through the Go runtime and is not instant.
        val links = withContext(Dispatchers.IO) { convertOrEmpty(text) }

        val profile = links.firstNotNullOfOrNull { VlessLinkParser.parse(it) }
        if (profile == null) {
            inputError.value = R.string.error_no_server_in_json
        }
        return profile
    }

    /**
     * §5.6: the core's error message quotes the config back, so it must not
     * reach the UI or a log. The exception is deliberately discarded here rather
     * than propagated — [parseInput] turns an empty result into a generic
     * message, and the detail dies at this boundary.
     */
    @Suppress("SwallowedException")
    private fun convertOrEmpty(xrayJson: String): List<String> =
        try {
            ShareLinkConverter.xrayJsonToShareLinks(xrayJson)
        } catch (e: XrayException) {
            emptyList()
        }

    /**
     * Called once VPN consent has been granted.
     *
     * Consent is the Activity's job — a ViewModel cannot launch an intent for
     * a result — so the screen asks first and calls this only on approval.
     */
    fun onConsentGranted() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val profile = parseInput(input.value)
            if (profile != null) {
                tunnel.connect(profile)
            }
            busy.value = false
        }
    }

    fun onDisconnect() {
        tunnel.disconnect()
    }
}
