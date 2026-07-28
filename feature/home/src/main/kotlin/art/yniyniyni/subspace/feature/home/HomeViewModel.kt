// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.SubscriptionParser
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
    /**
     * Typed detail from [SubscriptionParser]. M3's import UI renders it from
     * localized resources; this temporary M1 screen shows [inputError] only.
     */
    val inputErrorDetail: FailureDetail? = null,
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
    private val inputErrorDetail = MutableStateFlow<FailureDetail?>(null)
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
     *   generic reason and [inputErrorDetail] the parser's typed detail, when
     *   it has one. The detail has no free-text channel (§5.6).
     */
    private suspend fun parseInput(raw: String): Profile? {
        // §5.3: the connect button must stay responsive. `viewModelScope` runs
        // on Dispatchers.Main.immediate, so parsing here directly would do the
        // whole pass on the UI thread — a SHA-256 plus regex validation per
        // entry, and a YAML document parse per Clash entry, across a
        // subscription that is routinely hundreds of entries long.
        //
        // Default, not IO: this is pure CPU work with no blocking call in it.
        // IO's pool is sized for threads parked on syscalls, and putting
        // compute there both misprices the thread and competes with the real
        // blocking work §5.3 names (subscription fetch, libXray start/stop).
        val outcome = withContext(Dispatchers.Default) { SubscriptionParser.parse(raw) }
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
