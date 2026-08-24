// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.GeoInstallResult
import art.yniyniyni.subspace.core.data.InstalledGeoAsset
import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.GeoSource
import art.yniyniyni.subspace.core.model.GeoSourceCatalogue
import art.yniyniyni.subspace.core.model.PingMode

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
