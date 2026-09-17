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
) {
    /** One row per install filename. See [geoRowsFor]'s KDoc for exactly how ground truth is chosen. */
    val geoRows: List<GeoRow>
        get() = geoRowsFor(geoSources, selectedGeoSourceIds, geoInstalledAssets)

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
