// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.GeoInstallRequest
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoSourceCatalogue
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
// One setter per persisted preference, plus geo's three actions (Task 17) — the same shape
// GeoAssetRepository's own TooManyFunctions suppression documents: splitting this by preference
// would only move the count into more, smaller classes, not reduce it.
@Suppress("TooManyFunctions")
@HiltViewModel
internal class SettingsViewModel
@Inject
constructor(
    private val settingsSource: SettingsSource,
    private val xraySource: XraySource,
    appVersionSource: AppVersionSource,
    private val geoAssetSource: GeoAssetSource,
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

        settingsSource.geoRefreshOnMetered
            .onEach { enabled -> _state.update { it.copy(geoRefreshOnMetered = enabled) } }
            .launchIn(viewModelScope)

        geoAssetSource.installedAssets
            .onEach { assets -> _state.update { it.copy(geoInstalledAssets = assets) } }
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

    /**
     * Swaps which catalogue source backs [sourceId]'s [art.yniyniyni.subspace.core.model.GeoDataKind]:
     * any previously selected source for the same `installFileName` is dropped, since both would
     * write the same file and only one can ever be the "currently selected" one. Downloads
     * nothing by itself — [onGeoUpdateNow] is the action that does.
     */
    fun onGeoSourceSelected(sourceId: String) {
        val source = GeoSourceCatalogue.source(sourceId) ?: return
        _state.update { current ->
            val retainedIds =
                current.selectedGeoSourceIds
                    .mapNotNull(GeoSourceCatalogue::source)
                    .filter { it.installFileName != source.installFileName }
                    .map { it.id }
            current.copy(selectedGeoSourceIds = (retainedIds + source.id).toSet())
        }
    }

    /**
     * "Update now" for one row. Always runs — ignoring both the 7-day freshness cap and the
     * unmetered constraint (§A.5) — because [GeoAssetSource.install] itself never consults either;
     * there is no separate bypass to wire here.
     */
    fun onGeoUpdateNow(row: GeoRow) {
        val request =
            GeoInstallRequest(
                fileName = row.installFileName,
                sourceUrl = row.downloadUrl,
                geoType = row.geoType,
            )
        runGeoInstall(fileName = row.installFileName, request = request)
    }

    /**
     * Installs a user-supplied source immediately. [geoType] is a required parameter, not a
     * default — the brief is explicit that it cannot be inferred from the bytes (Part 1 Task 6),
     * and a wrong choice is a download that succeeds while every rule using it silently matches
     * nothing.
     *
     * §5.6: [url] is never logged here or anywhere downstream — [GeoInstallResult] is a closed
     * vocabulary that carries no URL, and that is the only thing this method's own callers ever
     * see back.
     */
    fun onAddCustomGeoSource(url: String, fileName: String, geoType: GeoDataKind) {
        runGeoInstall(
            fileName = fileName,
            request = GeoInstallRequest(fileName = fileName, sourceUrl = url, geoType = geoType),
        )
    }

    private fun runGeoInstall(fileName: String, request: GeoInstallRequest) {
        viewModelScope.launch {
            _state.update { it.copy(geoUpdateInFlight = it.geoUpdateInFlight + fileName) }
            val result = geoAssetSource.install(request)
            _state.update {
                it.copy(
                    geoUpdateInFlight = it.geoUpdateInFlight - fileName,
                    geoUpdateResults = it.geoUpdateResults + (fileName to result),
                )
            }
        }
    }

    fun onGeoRefreshOnMeteredChanged(enabled: Boolean) {
        viewModelScope.launch { settingsSource.setGeoRefreshOnMetered(enabled) }
    }
}
