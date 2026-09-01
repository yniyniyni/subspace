// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub

import kotlinx.coroutines.flow.Flow
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.ThemePreference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [SettingsRepository] slice [MainActivity] needs to resolve
 * [space.getsub.core.ui.theme.SubspaceTheme]'s `darkTheme` argument.
 *
 * [SettingsRepository] has an `internal` constructor scoped to `:core:data`
 * (ARCHITECTURE.md §3) — this module cannot build a real instance of it to
 * test against, the same reason
 * [space.getsub.feature.settings.SettingsSource] and
 * [space.getsub.feature.home.ActiveProfileSource] exist.
 * [BoundThemeSource] is the one place that touches the real repository.
 */
internal interface ThemeSource {
    /** The current theme preference. See [SettingsRepository.theme]. */
    val theme: Flow<ThemePreference>
}

@Singleton
internal class BoundThemeSource
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
) : ThemeSource {
    override val theme: Flow<ThemePreference> = settingsRepository.theme
}
