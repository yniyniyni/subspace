// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import art.yniyniyni.subspace.core.data.ThemePreference

/**
 * Resolves [art.yniyniyni.subspace.core.ui.theme.SubspaceTheme]'s `darkTheme`
 * argument from the stored [ThemePreference] and the system's own dark-mode
 * state.
 *
 * [ThemePreference.Light] and [ThemePreference.Dark] override the system;
 * [ThemePreference.System] follows it. `null` covers the window before
 * [ThemeSource.theme]'s first emission from Room — [MainActivity] holds the
 * first frame until that resolves, but this still needs a value to fall back
 * to in the meantime, and following the system is the least-surprising one.
 */
internal fun resolveDarkTheme(
    preference: ThemePreference?,
    systemInDarkTheme: Boolean,
): Boolean =
    when (preference) {
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
        ThemePreference.System, null -> systemInDarkTheme
    }
