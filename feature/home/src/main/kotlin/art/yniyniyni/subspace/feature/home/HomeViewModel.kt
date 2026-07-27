// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the screen renders. */
internal data class HomeState(
    val input: String = "",
    val connection: ConnectionState = ConnectionState.Disconnected,
    /** Null when there is nothing to complain about. A string resource id. */
    val inputError: Int? = null,
    /**
     * Redacted detail from [SubscriptionParser] (§5.6), shown alongside
     * [inputError] when the parser has more to say than the generic message.
     */
    val inputErrorDetail: String? = null,
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
    private val inputErrorDetail = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        // §5.5: the connection half of this state comes from the service and
        // is only ever mirrored here. The text field is the only part the UI
        // actually owns.
        combine(
            input,
            tunnel.state,
            inputError,
            inputErrorDetail,
            busy,
        ) { text, conn, error, detail, working ->
            HomeState(
                input = text,
                connection = conn,
                inputError = error,
                inputErrorDetail = detail,
                busy = working,
            )
        }.onEach { _state.value = it }
            .launchIn(viewModelScope)
    }

    fun onInputChanged(value: String) {
        input.value = value
        inputError.value = null
        inputErrorDetail.value = null
    }

    /**
     * Turns whatever was pasted into a [Profile] via [SubscriptionParser] —
     * the single entry point for `:core:parser`, which handles link,
     * base64, Clash YAML, and raw Xray JSON detection itself (§7).
     *
     * @return null if nothing usable came out; [inputError] then holds the
     *   generic reason and [inputErrorDetail] the parser's redacted detail,
     *   when it has one (§5.6: already redacted, safe to surface — §10.4,
     *   it is the only diagnostic a user can hand back).
     */
    private fun parseInput(raw: String): Profile? {
        val outcome = SubscriptionParser.parse(raw)
        val profile = outcome.profiles.firstOrNull()
        if (profile == null) {
            inputError.value = R.string.error_unparseable
            inputErrorDetail.value = outcome.failures.firstOrNull()?.detail
        }
        return profile
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
