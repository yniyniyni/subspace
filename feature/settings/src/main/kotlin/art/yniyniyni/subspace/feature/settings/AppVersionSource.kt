// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This app's own `versionName` — not Xray-core's, see [XraySource] for that
 * one. Read from [PackageManager] rather than `BuildConfig`: `:feature:settings`
 * cannot depend on `:app` (§4 — feature modules never sit above `:app` in the
 * graph), so there is no `BuildConfig.VERSION_NAME` this module can see, and a
 * runtime lookup of the app's own installed package works regardless of how
 * the module graph is wired.
 */
internal interface AppVersionSource {
    /** The installed app's `versionName`, or an empty string if it cannot be read. */
    val version: String
}

@Singleton
internal class BoundAppVersionSource
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) : AppVersionSource {
    // A lookup of the app's own package is not expected to fail — this
    // guards the theoretical PackageManager.NameNotFoundException path
    // rather than letting a Settings screen crash over an About-section
    // string, the same defensive shape SettingsRepository.theme's fallback
    // uses for a value that should always be well-formed.
    override val version: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
}
