// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import space.getsub.core.data.GeoInstallRequest
import space.getsub.core.data.GeoInstallResult
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.DnsValidation
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.GeoSourceCatalogue
import space.getsub.core.model.PingMode
import space.getsub.core.model.isGeoFileName
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
    private val dnsWriteRequests = Channel<DnsWriteRequest>(Channel.CONFLATED)
    private var dnsRevision = 0L
    private var editableDnsRevision: Long? = null
    private var latestPersistedDnsResolver = DnsResolver.DEFAULT

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
                latestPersistedDnsResolver = resolver
                if (editableDnsRevision == null) {
                    _state.update { it.withDnsResolver(resolver) }
                }
            }.launchIn(viewModelScope)

        viewModelScope.launch {
            for (request in dnsWriteRequests) {
                persistDnsWrite(request)
            }
        }

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

        settingsSource.bootAutostart
            .onEach { enabled -> _state.update { it.copy(bootAutostart = enabled) } }
            .launchIn(viewModelScope)

        settingsSource.failClosed
            .onEach { enabled -> _state.update { it.copy(failClosed = enabled) } }
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

    fun onBootAutostartChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsSource.setBootAutostart(enabled)
            maybePromptForBattery(justEnabled = enabled)
        }
    }

    fun onFailClosedChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsSource.setFailClosed(enabled)
            maybePromptForBattery(justEnabled = enabled)
        }
    }

    /**
     * Spec §7.2, ARCHITECTURE.md §9: Doze is worth raising at the moment the user says they want
     * the tunnel to survive, and nowhere else — not at construction, not on an unrelated setting
     * ([shouldPromptForBattery]'s own KDoc).
     *
     * [justEnabled] carries that decision rather than the call sites doing it. It used to be
     * passed as a literal `true`, with each caller guarding itself with `if (enabled)` — so the
     * parameter could not be false in production and [shouldPromptForBattery]'s first conjunct
     * was decoration, tested but never exercised. Switching a survival setting *off* now reaches
     * this function and is refused here, in the one place that decides.
     */
    private suspend fun maybePromptForBattery(justEnabled: Boolean) {
        // No early return on [justEnabled], deliberately. One was added here to skip the two
        // reads below when the outcome is already determined, and it reinstated precisely the
        // property `be1ee7f` removed: with it, [shouldPromptForBattery]'s first conjunct could
        // not be false in production, so the conjunct was decoration again and
        // `turning a survival setting off does not prompt` passed for two independent reasons —
        // delete that conjunct and the test still passed, pinning nothing. Its cost is a
        // PowerManager binder round trip and a Room read, performed and discarded, on a
        // user-initiated toggle *off*; that is cheaper than a predicate whose decisive input no
        // call site can produce.
        //
        // A binder round trip to PowerManager; SettingsSource does it on IO (§5.3), so this stays
        // a plain suspending call rather than each caller choosing a dispatcher.
        val ignoringOptimisations = settingsSource.isIgnoringBatteryOptimizations()
        val show =
            shouldPromptForBattery(
                alreadyShown = settingsSource.batteryPromptShown.first(),
                isIgnoringOptimisations = ignoringOptimisations,
                survivalSettingJustEnabled = justEnabled,
            )
        if (show) _state.update { it.copy(showBatteryPrompt = true) }
    }

    /**
     * Spec §7.2 names three triggers — always-on, boot autostart and fail-closed — and only the
     * latter two raised the prompt.
     *
     * Always-on is a deep link to system settings (§7.1: this app cannot set it, so it is a link
     * and never a switch), which means the app never learns whether the user actually enabled it.
     * Tapping the row is the strongest statement of "I want this tunnel to survive" that is
     * observable here, so that is what the prompt is hung on. The prompt is shown once ever
     * (`batteryPromptShown`), so treating the tap as intent cannot become nagging.
     */
    fun onAlwaysOnOpened() {
        viewModelScope.launch { maybePromptForBattery(justEnabled = true) }
    }

    /**
     * Either response to the battery prompt — including a plain dismissal — records
     * [SettingsSource.batteryPromptShown] so the prompt never returns. "Respect refusal" (§9)
     * means this call happens whatever the user chose, not only on acceptance.
     */
    fun onBatteryPromptResolved() {
        _state.update { it.copy(showBatteryPrompt = false) }
        viewModelScope.launch { settingsSource.setBatteryPromptShown(true) }
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
        if (_state.value.dnsTransport == transport) return
        updateDnsDraft { current -> current.copy(dnsTransport = transport, dnsAddress = "", dnsBootstrapIp = "") }
    }

    fun onDnsAddressChanged(address: String) {
        updateDnsDraft { current -> current.copy(dnsAddress = address) }
    }

    fun onDnsBootstrapIpChanged(bootstrapIp: String) {
        updateDnsDraft { current -> current.copy(dnsBootstrapIp = bootstrapIp) }
    }

    private fun updateDnsDraft(transform: (SettingsState) -> SettingsState) {
        val updated = transform(_state.value)
        if (updated == _state.value) return

        val revision = ++dnsRevision
        editableDnsRevision = revision
        _state.value = updated
        updated.dnsResolverOrNull()?.let { resolver ->
            dnsWriteRequests.trySend(DnsWriteRequest(revision = revision, resolver = resolver))
        }
    }

    /** Serializes failure recovery with the revision that owns the visible DNS draft. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun persistDnsWrite(request: DnsWriteRequest) {
        try {
            settingsSource.setDnsResolver(request.resolver)
            if (editableDnsRevision == request.revision) {
                editableDnsRevision = null
                _state.update { it.withDnsResolver(request.resolver) }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            if (editableDnsRevision == request.revision) {
                editableDnsRevision = null
                _state.update { it.withDnsResolver(latestPersistedDnsResolver) }
            }
        }
    }

    /**
     * Swaps which catalogue source backs [sourceId]'s [space.getsub.core.model.GeoDataKind]:
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
     * [space.getsub.feature.routing.RuleSetEditorViewModel.save]'s own KDoc documents. A
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

/**
 * Spec §7.2. ARCHITECTURE.md §9: "Prompt the user to exempt the app, or the tunnel dies in Doze.
 * Prompt once, respect refusal."
 *
 * [survivalSettingJustEnabled] is the trigger — always-on, boot autostart or fail-closed being
 * switched on. Those are the moments the user has said they want the tunnel to survive, which is
 * when Doze is worth raising.
 */
internal fun shouldPromptForBattery(
    alreadyShown: Boolean,
    isIgnoringOptimisations: Boolean,
    survivalSettingJustEnabled: Boolean,
): Boolean = survivalSettingJustEnabled && !alreadyShown && !isIgnoringOptimisations

private fun SettingsState.dnsResolverOrNull(): DnsResolver? =
    when (dnsTransport) {
        DnsTransport.DOH ->
            dnsAddress
                .trim()
                .takeIf(DnsValidation::isHttpsUrl)
                ?.takeIf { dnsBootstrapIp.trim().isBlank() || DnsValidation.isAddressLiteral(dnsBootstrapIp.trim()) }
                ?.let { domain ->
                    DnsResolver(
                        transport = DnsTransport.DOH,
                        domain = domain,
                        ip = dnsBootstrapIp.trim().takeIf(String::isNotBlank),
                    )
                }

        DnsTransport.DOU ->
            dnsAddress.trim().takeIf(DnsValidation::isAddressLiteral)?.let { ip ->
                DnsResolver(DnsTransport.DOU, ip = ip)
            }
    }

private fun SettingsState.withDnsResolver(resolver: DnsResolver): SettingsState =
    copy(
        dnsTransport = resolver.transport,
        dnsAddress = resolver.xrayAddress().orEmpty(),
        dnsBootstrapIp = if (resolver.transport == DnsTransport.DOH) resolver.ip.orEmpty() else "",
    )

private data class DnsWriteRequest(
    val revision: Long,
    val resolver: DnsResolver,
) {
    override fun toString(): String = "DnsWriteRequest(revision=$revision, resolver=<redacted>)"
}
