// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.ThemePreference
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [SettingsRepository] slice this screen needs.
 *
 * [SettingsRepository] has an `internal` constructor scoped to `:core:data`
 * (§3 keeps settings behind a typed repository, not a key/value table any
 * module can poke) — this module cannot build a real instance of it to test
 * against, the same reason
 * [art.yniyniyni.subspace.feature.profiles.ProfileSource] and
 * [art.yniyniyni.subspace.feature.home.ActiveProfileSource] exist.
 * [BoundSettingsSource] is the one place that touches the real repository;
 * [SettingsViewModel] goes through this interface instead, so a plain JVM
 * test can exercise it against a fake.
 */
internal interface SettingsSource {
    /** The current theme preference. See [SettingsRepository.theme]. */
    val theme: Flow<ThemePreference>

    /** Persists the theme preference. See [SettingsRepository.setTheme]. */
    suspend fun setTheme(preference: ThemePreference)
}

@Singleton
internal class BoundSettingsSource
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
) : SettingsSource {
    override val theme: Flow<ThemePreference> = settingsRepository.theme

    override suspend fun setTheme(preference: ThemePreference) = settingsRepository.setTheme(preference)
}
