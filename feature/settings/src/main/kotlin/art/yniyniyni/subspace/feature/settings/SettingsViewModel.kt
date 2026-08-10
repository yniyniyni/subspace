// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.model.PingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Settings screen: Appearance (theme, persisted through
 * [SettingsSource] — ARCHITECTURE.md §3 rules out an in-memory
 * `mutableStateOf` here, since `:main` and `:bg` are separate processes and
 * only Room crosses that boundary) and About (this app's own version, plus
 * Xray-core's, read once at construction since neither changes while the
 * screen is open).
 *
 * [theme] is re-read from [SettingsSource] on every construction rather than
 * cached in a local field — the only way a restarted `ViewModel` (a process
 * restart, or the test in `SettingsViewModelTest` that constructs a second
 * instance against the same fake) reflects a value persisted by a previous
 * one.
 */
@HiltViewModel
internal class SettingsViewModel
@Inject
constructor(
    private val settingsSource: SettingsSource,
    private val xraySource: XraySource,
    appVersionSource: AppVersionSource,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState(appVersion = appVersionSource.version))
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        settingsSource.theme
            .onEach { theme -> _state.update { it.copy(theme = theme) } }
            .launchIn(viewModelScope)

        settingsSource.hwidEnabled
            .onEach { enabled -> _state.update { it.copy(hwidEnabled = enabled, hwid = settingsSource.hwid) } }
            .launchIn(viewModelScope)

        settingsSource.pingMode
            .onEach { mode -> _state.update { it.copy(pingMode = mode) } }
            .launchIn(viewModelScope)

        settingsSource.pingCheckUrl
            .onEach { url -> _state.update { it.copy(pingCheckUrl = url) } }
            .launchIn(viewModelScope)

        settingsSource.pingTimeoutSeconds
            .onEach { seconds -> _state.update { it.copy(pingTimeoutSeconds = seconds) } }
            .launchIn(viewModelScope)

        settingsSource.pingOnLaunch
            .onEach { enabled -> _state.update { it.copy(pingOnLaunch = enabled) } }
            .launchIn(viewModelScope)

        settingsSource.pingOnLaunchMetered
            .onEach { enabled -> _state.update { it.copy(pingOnLaunchMetered = enabled) } }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            val version = xraySource.version()
            _state.update {
                it.copy(
                    xrayVersion =
                    version.fold(
                        onSuccess = XrayVersionState::Available,
                        // §10.4: a failed call surfaces as its own named
                        // state, never a blank string that reads as a real
                        // (if empty) answer.
                        onFailure = { XrayVersionState.Unavailable },
                    ),
                )
            }
        }
    }

    fun onThemeChanged(preference: ThemePreference) {
        viewModelScope.launch { settingsSource.setTheme(preference) }
    }

    fun onHwidEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { settingsSource.setHwidEnabled(enabled) }
    }

    fun onPingModeChanged(mode: PingMode) {
        viewModelScope.launch { settingsSource.setPingMode(mode) }
    }

    fun onPingCheckUrlChanged(url: String) {
        viewModelScope.launch { settingsSource.setPingCheckUrl(url) }
    }

    /**
     * The repository clamps to 1–15 on both write and read, so a value from a
     * stepper that runs away — or from a hand-edited row — cannot reach libXray,
     * where this is **seconds** and a large number is a measurement that never
     * returns.
     */
    fun onPingTimeoutChanged(seconds: Int) {
        viewModelScope.launch { settingsSource.setPingTimeoutSeconds(seconds) }
    }

    fun onPingOnLaunchChanged(enabled: Boolean) {
        viewModelScope.launch { settingsSource.setPingOnLaunch(enabled) }
    }

    fun onPingOnLaunchMeteredChanged(enabled: Boolean) {
        viewModelScope.launch { settingsSource.setPingOnLaunchMetered(enabled) }
    }
}
