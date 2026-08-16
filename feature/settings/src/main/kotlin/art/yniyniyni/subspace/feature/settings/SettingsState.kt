// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.GeoInstallResult
import art.yniyniyni.subspace.core.data.InstalledGeoAsset
import art.yniyniyni.subspace.core.data.ThemePreference
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
    /** The full catalogue, for the source picker. */
    val geoSources: List<GeoSource> = GeoSourceCatalogue.sources,
    /**
     * Which catalogue row backs each install slot. Starts at [GeoSourceCatalogue.defaults] — the
     * v2fly geoip/geosite pair — for a user who never opens the picker. Picking a different
     * source of the same [art.yniyniyni.subspace.core.model.GeoDataKind] replaces the id for that
     * slot rather than adding a second one, since both share the same `installFileName` and can
     * only ever have one file on disk at a time.
     */
    val selectedGeoSourceIds: Set<String> = GeoSourceCatalogue.defaults().map { it.id }.toSet(),
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
    /**
     * One row per install filename: the selected catalogue sources, plus any installed asset
     * (including a user-added custom one) that isn't already covered by a selection.
     */
    val geoRows: List<GeoRow>
        get() {
            val selectedSources =
                selectedGeoSourceIds
                    .mapNotNull(GeoSourceCatalogue::source)
                    .distinctBy { it.installFileName }
            val selectedFileNames = selectedSources.map { it.installFileName }.toSet()
            val catalogueRows =
                selectedSources.map { source ->
                    geoRowFor(source, geoInstalledAssets.firstOrNull { it.fileName == source.installFileName })
                }
            val customRows =
                geoInstalledAssets
                    .filter { it.fileName !in selectedFileNames }
                    .map { asset -> geoRowFor(asset.asCustomSource(), asset, isCustom = true) }
            return catalogueRows + customRows
        }

    /** Keep the provider-facing identifier visible in the UI, never in diagnostic output (§5.6). */
    override fun toString(): String =
        "SettingsState(theme=$theme, appVersion=$appVersion, xrayVersion=$xrayVersion, " +
            "hwidEnabled=$hwidEnabled, hwid=<redacted>)"
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
