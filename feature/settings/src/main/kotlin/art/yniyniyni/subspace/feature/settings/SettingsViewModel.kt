// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.GeoInstallRequest
import art.yniyniyni.subspace.core.data.GeoInstallResult
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.DnsValidation
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoSourceCatalogue
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.model.isGeoFileName
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
    private var hasEditableDnsDraft = false

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

        settingsSource.dnsResolver
            .onEach { resolver ->
                if (!hasEditableDnsDraft) {
                    _state.update {
                        it.copy(
                            dnsTransport = resolver.transport,
                            dnsAddress = resolver.xrayAddress().orEmpty(),
                            dnsBootstrapIp = if (resolver.transport == DnsTransport.DOH) resolver.ip.orEmpty() else "",
                        )
                    }
                }
            }.launchIn(viewModelScope)

        settingsSource.dnsOverriddenByProfile
            .onEach { overridden -> _state.update { it.copy(dnsOverriddenByProfile = overridden) } }
            .launchIn(viewModelScope)

        settingsSource.geoRefreshOnMetered
            .onEach { enabled -> _state.update { it.copy(geoRefreshOnMetered = enabled) } }
            .launchIn(viewModelScope)

        // Persisted (branch review, Finding 1) — a deliberate picker switch must survive a
        // restart, the same reason `theme` is re-read here rather than cached in a local field.
        settingsSource.selectedGeoSourceIds
            .onEach { ids -> _state.update { it.copy(selectedGeoSourceIds = ids) } }
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

    /** Starts a new local resolver draft; persistence waits for a complete valid resolver. */
    fun onDnsTransportChanged(transport: DnsTransport) {
        hasEditableDnsDraft = true
        _state.update { current ->
            if (current.dnsTransport == transport) {
                current
            } else {
                current.copy(dnsTransport = transport, dnsAddress = "", dnsBootstrapIp = "")
            }
        }
    }

    fun onDnsAddressChanged(address: String) {
        hasEditableDnsDraft = true
        _state.update { it.copy(dnsAddress = address) }
        persistDnsResolverIfValid()
    }

    fun onDnsBootstrapIpChanged(bootstrapIp: String) {
        hasEditableDnsDraft = true
        _state.update { it.copy(dnsBootstrapIp = bootstrapIp) }
        persistDnsResolverIfValid()
    }

    private fun persistDnsResolverIfValid() {
        val resolver = _state.value.dnsResolverOrNull() ?: return
        viewModelScope.launch {
            settingsSource.setDnsResolver(resolver)
            hasEditableDnsDraft = false
        }
    }

    /**
     * Swaps which catalogue source backs [sourceId]'s [art.yniyniyni.subspace.core.model.GeoDataKind]:
     * any previously selected source for the same `installFileName` is dropped, since both would
     * write the same file and only one can ever be the "currently selected" one. Downloads
     * nothing by itself — [onGeoUpdateNow] is the action that does. Persisted through
     * [SettingsSource.setSelectedGeoSourceIds] rather than mutated only in `_state` (branch
     * review, Finding 1), so this survives a restart.
     */
    fun onGeoSourceSelected(sourceId: String) {
        val source = GeoSourceCatalogue.source(sourceId) ?: return
        viewModelScope.launch {
            settingsSource.setSelectedGeoSourceIds(replaceSelectionForFileName(source.installFileName, source.id))
        }
    }

    /** [selectedGeoSourceIds] with any entry for [fileName] dropped, and [newId] added if not null. */
    private fun replaceSelectionForFileName(fileName: String, newId: String?): Set<String> {
        val retained =
            _state.value.selectedGeoSourceIds
                .mapNotNull(GeoSourceCatalogue::source)
                .filter { it.installFileName != fileName }
                .map { it.id }
        return (if (newId != null) retained + newId else retained).toSet()
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
        viewModelScope.launch { runGeoInstall(row.installFileName, request) }
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
     *
     * [fileName] is checked against [isGeoFileName] before anything is sent anywhere (branch
     * review minor): [GeoCustomSourceForm]'s own Add button already disables for a shape like
     * `geoip` with no extension, but this is the one place that guarantee holds even if a future
     * caller skips the form — without it, a bad filename still reaches the repository, which
     * rejects it as `InvalidFileName`, surfacing as "Downloaded file was not a valid geo database"
     * for bytes that were never fetched at all.
     */
    fun onAddCustomGeoSource(url: String, fileName: String, geoType: GeoDataKind) {
        if (!isGeoFileName(fileName)) {
            _state.update { it.copy(geoUpdateResults = it.geoUpdateResults + (fileName to GeoInstallResult.Rejected)) }
            return
        }
        viewModelScope.launch {
            val request = GeoInstallRequest(fileName = fileName, sourceUrl = url, geoType = geoType)
            val result = runGeoInstall(fileName, request)
            if (result == GeoInstallResult.Installed) {
                // No catalogue id names a custom source, so clearing (rather than replacing) any
                // conflicting selection is what lets geoRowsFor's ground-truth rule show this row
                // immediately, instead of a stale pending catalogue pick for the same filename
                // (branch review, Finding 1's other half).
                settingsSource.setSelectedGeoSourceIds(replaceSelectionForFileName(fileName, newId = null))
            }
        }
    }

    /**
     * §10.4 (branch review, Finding 4): [geoUpdateInFlight] is added before the call and must come
     * back out on every exit, not only the ordinary return — [GeoAssetSource.install] is documented
     * never to throw, so this `finally` is defensive rather than fixing an observed hang, but a
     * spinner stranded by some future exception in this path (or plain cancellation) is exactly the
     * silent-bad-state shape §10.4 asks this codebase not to risk for one line.
     */
    private suspend fun runGeoInstall(fileName: String, request: GeoInstallRequest): GeoInstallResult {
        _state.update { it.copy(geoUpdateInFlight = it.geoUpdateInFlight + fileName) }
        try {
            val result = geoAssetSource.install(request)
            _state.update { it.copy(geoUpdateResults = it.geoUpdateResults + (fileName to result)) }
            return result
        } finally {
            _state.update { it.copy(geoUpdateInFlight = it.geoUpdateInFlight - fileName) }
        }
    }

    fun onGeoRefreshOnMeteredChanged(enabled: Boolean) {
        viewModelScope.launch { settingsSource.setGeoRefreshOnMetered(enabled) }
    }

    /**
     * Drops the recorded row for [fileName] — see [GeoAssetSource.remove]'s KDoc (branch review,
     * Finding 3). Restricted to a custom (non-catalogue) row both here and in the affordance that
     * calls this: the same "a disabled control is a hint, the gate belongs here too" reasoning
     * [art.yniyniyni.subspace.feature.routing.RuleSetEditorViewModel.save]'s own KDoc documents. A
     * catalogue row would simply reappear the next time its source is selected, so removing one
     * would be confusing at best — this silently ignores that case rather than surfacing an error
     * for a button [SettingsGeoSection] never shows on a catalogue row in the first place.
     */
    fun onRemoveCustomGeoSource(fileName: String) {
        val row = _state.value.geoRows.firstOrNull { it.installFileName == fileName } ?: return
        if (!row.isCustom) return
        viewModelScope.launch { geoAssetSource.remove(fileName) }
    }
}

private fun SettingsState.dnsResolverOrNull(): DnsResolver? =
    when (dnsTransport) {
        DnsTransport.DOH ->
            dnsAddress
                .takeIf(DnsValidation::isHttpsUrl)
                ?.takeIf { dnsBootstrapIp.isBlank() || DnsValidation.isAddressLiteral(dnsBootstrapIp) }
                ?.let { domain ->
                    DnsResolver(
                        transport = DnsTransport.DOH,
                        domain = domain,
                        ip = dnsBootstrapIp.takeIf(String::isNotBlank),
                    )
                }

        DnsTransport.DOU ->
            dnsAddress.takeIf(DnsValidation::isAddressLiteral)?.let { ip -> DnsResolver(DnsTransport.DOU, ip = ip) }
    }
