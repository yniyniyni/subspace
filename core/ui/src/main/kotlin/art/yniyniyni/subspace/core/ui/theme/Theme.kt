// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Subspace's Material theme: the ported color schemes, typography, shapes
 * and the [LocalSubspaceColors] this design system needs beyond Material's
 * own roles.
 *
 * Dynamic color (Material You / wallpaper-derived palettes) is deliberately
 * **not** offered here — there is no `dynamicColor` parameter, and none
 * should be added. A wallpaper-derived scheme would replace
 * `--color-connected` with whatever tone Android's algorithm assigns it, and
 * that color is not decorative: the design system reserves it exclusively
 * for an established tunnel (see [SubspaceColors]), and §5.5 makes
 * connection state the one thing this app's UI must never misreport.
 * Dynamic color can silently reassign a semantic meaning that this app
 * treats as a correctness property, not a style choice — so it stays off,
 * on every Android version, unconditionally.
 *
 * @param darkTheme whether to use [SubspaceDarkColorScheme]/[subspaceDarkColors]
 *   rather than the light pair. Defaults to the system setting.
 */
@Composable
fun SubspaceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) SubspaceDarkColorScheme else SubspaceLightColorScheme
    val subspaceColors = if (darkTheme) subspaceDarkColors else subspaceLightColors

    CompositionLocalProvider(LocalSubspaceColors provides subspaceColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SubspaceTypography,
            shapes = SubspaceShapes,
            content = content,
        )
    }
}
