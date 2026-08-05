// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.xray.xrayCoreVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Xray-core version the About section shows, wrapped behind an
 * interface so [SettingsViewModel] can be exercised with a plain fake — a
 * JVM unit test cannot reach the real vendored Go runtime
 * [art.yniyniyni.subspace.core.xray.xrayCoreVersion] calls into, the same
 * reason [SettingsSource] wraps [art.yniyniyni.subspace.core.data.SettingsRepository]
 * (different cause, same shape: this module cannot build a real,
 * test-usable instance of the thing behind it).
 */
internal interface XraySource {
    /**
     * The vendored libXray release's own version string.
     *
     * §10.4: this is a real failure path, not an assumed success — a
     * [Result] failure means the native call threw (see
     * [art.yniyniyni.subspace.core.xray.xrayCoreVersion]'s own KDoc), and
     * [SettingsViewModel] renders that as [XrayVersionState.Unavailable]
     * rather than crashing the screen or showing a blank string that looks
     * like a real answer.
     */
    suspend fun version(): Result<String>
}

@Singleton
internal class BoundXraySource
@Inject
constructor() : XraySource {
    override suspend fun version(): Result<String> = runCatching { xrayCoreVersion() }
}
