// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.ThemePreference
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
    val pingMode: PingMode = PingMode.PROXY_HEAD,
    val pingCheckUrl: String = "",
    val pingTimeoutSeconds: Int = 5,
    val pingOnLaunch: Boolean = true,
    val pingOnLaunchMetered: Boolean = false,
) {
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
