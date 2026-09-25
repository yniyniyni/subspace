// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import space.getsub.core.data.GeoInstallResult
import space.getsub.core.data.InstalledGeoAsset
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.GeoSource
import space.getsub.core.model.GeoSourceCatalogue
import space.getsub.core.model.PingMode

/**
 * What the Settings screen shows: Appearance's current choice and About's
 * three facts.
 */
internal data class SettingsState(
    val theme: ThemePreference = ThemePreference.System,
    val appVersion: String = "",
    val xrayVersion: XrayVersionState = XrayVersionState.Loading,
    val hwidEnabled: Boolean = true,
    val hwid: String = "",
    /**
     * How latency is measured.
     *
     * Only two modes exist. `icmp` needs root (Appendix D), and a GET-based
     * `proxy` mode is not implementable against libXray v26.7.11 — its
     * `MeasureDelay` hardcodes an HTTP HEAD. A provider's `ping-type: proxy`
     * therefore resolves to [PingMode.PROXY_HEAD], which is why the UI label for
     * it says "(HEAD)" rather than a bare "Proxy".
     */
    val pingMode: PingMode = PingMode.TCP,
    val pingCheckUrl: String = "",
    val pingTimeoutSeconds: Int = 5,
    val pingOnLaunch: Boolean = true,
    val pingOnLaunchMetered: Boolean = false,
    /** The resolver transport currently being edited, which may be ahead of the persisted value. */
    val dnsTransport: DnsTransport = DnsResolver.DEFAULT.transport,
    /** The editable endpoint: a DoH URL or a DoU address, depending on [dnsTransport]. */
    val dnsAddress: String = DnsResolver.DEFAULT.ip.orEmpty(),
    /** An optional literal bootstrap address for a DoH endpoint. */
    val dnsBootstrapIp: String = "",
    /** Whether the active routing profile currently supersedes the app-level resolver. */
    val dnsOverriddenByProfile: Boolean = false,
    /** The full catalogue, for the source picker. */
    val geoSources: List<GeoSource> = GeoSourceCatalogue.sources,
    /**
     * Which catalogue source id the user has explicitly picked for each install slot, persisted
     * through [SettingsSource.selectedGeoSourceIds] (branch review, Finding 1: this used to be an
     * in-memory default only, forgotten on every restart). Empty until the picker is used — the
     * fallback to [GeoSourceCatalogue.defaults] for a slot nobody has touched lives in [geoRows]'
     * own [geoRowsFor], not here, so this field can keep meaning exactly "what the user chose".
     */
    val selectedGeoSourceIds: Set<String> = emptySet(),
    /** Every recorded geo asset, from [GeoAssetSource.installedAssets]. */
    val geoInstalledAssets: List<InstalledGeoAsset> = emptyList(),
    /** The install filenames a manual "Update now" is currently running for. */
    val geoUpdateInFlight: Set<String> = emptySet(),
    /**
     * The last manual "Update now" outcome per install filename. §10.4: kept as the real
     * four-member [GeoInstallResult], not collapsed to a pass/fail boolean, so the UI can tell a
     * failed download from a rejected file from a failed local install.
     */
    val geoUpdateResults: Map<String, GeoInstallResult> = emptyMap(),
    /** Whether a *scheduled* geo refresh may run on a metered network. A manual update always can. */
    val geoRefreshOnMetered: Boolean = false,
    /** Connect every time the device starts (spec §4.2). See [SettingsSource.bootAutostart]. */
    val bootAutostart: Boolean = false,
    /**
     * Whether the TUN is retained while a wanted session is down (spec §6). Defaults to on —
     * matches [space.getsub.core.data.SettingsRepository.failClosed]'s own default.
     */
    val failClosed: Boolean = true,
    /**
     * Spec §7.2, ARCHITECTURE.md §9: "prompt once, respect refusal" — true for the one moment
     * between a survival setting (boot autostart or fail-closed) being switched on and the user
     * responding, per [shouldPromptForBattery]'s own gate in [SettingsViewModel].
     */
    val showBatteryPrompt: Boolean = false,
    /**
     * Whether xray's `stats`/`policy`/`metrics` blocks are emitted so the per-server traffic
     * breakdown (Task 7's [SettingsDiagnosticsSection]) can be shown on Home. Defaults to off —
     * matches [space.getsub.core.data.SettingsRepository.perTagBreakdown]'s own default, which
     * that property's KDoc documents as a security decision, not a taste one: see
     * [SettingsDiagnosticsSection]'s summary text for what turning it on exposes.
     */
    val perTagBreakdown: Boolean = false,
    /**
     * True while a tunnel session is up, mirrored from [TunnelSessionSource] (ARCHITECTURE.md
     * §5.5: never inferred locally — the same discipline
     * [space.getsub.feature.home.HomeState.connection] follows for the same underlying state).
     *
     * Drives [perTagBreakdownSessionNoticeVisible] directly: whether toggling [perTagBreakdown]
     * right now is a request the running core has not seen yet (ruling R43, revising F2 / ruling
     * R39), since [space.getsub.service.TunnelService.startCore] reads this setting once, at
     * connect, and is deliberately not restarted just to apply a diagnostic.
     */
    val sessionConnected: Boolean = false,
) {
    /** One row per install filename. See [geoRowsFor]'s KDoc for exactly how ground truth is chosen. */
    val geoRows: List<GeoRow>
        get() = geoRowsFor(geoSources, selectedGeoSourceIds, geoInstalledAssets)

    /**
     * Whether [SettingsDiagnosticsSection] shows the "applies to your next connection" notice
     * beneath the per-server breakdown switch (ruling R43, replacing review finding I-2's one-way
     * `perTagBreakdownPendingReconnect` latch).
     *
     * A `get()` over [sessionConnected], not a stored field the way the removed latch was — so
     * there is no write path for this to fall out of sync with the session it describes. The
     * latch had two reachable false-statement cases: re-toggling the switch back to the value the
     * running session already had (the latch re-armed on every change, in either direction, so it
     * kept naming the *previous* change rather than the running session — see F2's own on/off
     * strings, which this removes) and a screen navigation recreating the `ViewModel` (the latch
     * defaulted to false, silently losing the notice while the running session's exposure had not
     * changed). Deriving from [sessionConnected] alone closes both: the statement this drives —
     * "this session keeps the setting it started with; changes apply to your next connection" —
     * is true in every case and in both toggle directions, and [sessionConnected] itself already
     * survives a `ViewModel` recreation because [TunnelSessionSource.state] is a
     * [kotlinx.coroutines.flow.StateFlow] that a fresh subscriber reads at its current value
     * immediately, rather than starting from an assumed default.
     *
     * **Rejected for now, not because it is wrong but because of when this lands:** the more
     * precise answer is plumbing the real per-session fact —
     * `TunnelService.metricsPort != null`, i.e. whether *this* session's diagnostics port is
     * actually open — through `ConnectionStateParcel`, so this notice (and the switch itself)
     * could describe this session exactly rather than the coarser "a session is running" used
     * here. That is real Part 3 work: it adds AIDL surface, which this branch does not take on
     * immediately ahead of a device verification pass.
     */
    val perTagBreakdownSessionNoticeVisible: Boolean
        get() = sessionConnected

    /** Keep the provider-facing identifier visible in the UI, never in diagnostic output (§5.6). */
    override fun toString(): String =
        "SettingsState(theme=$theme, appVersion=$appVersion, xrayVersion=$xrayVersion, " +
            "hwidEnabled=$hwidEnabled, hwid=<redacted>, dns=<redacted>)"
}

/**
 * §10.4: the Xray-core version is a real value fetched from a native call
 * that can fail (see [XraySource.version]'s KDoc) — this names every state
 * that call can actually be in, rather than collapsing "still loading" and
 * "failed" into the same blank string a "succeeded with an empty answer"
 * would also produce.
 */
internal sealed interface XrayVersionState {
    data object Loading : XrayVersionState

    data class Available(val version: String) : XrayVersionState

    data object Unavailable : XrayVersionState
}
