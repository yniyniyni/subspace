// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The semantic colors Material has no role for.
 *
 * [connected] is not decoration. The design system's own note calls its halo
 * "the only glow in the system — reserved exclusively for an established
 * tunnel; never decorative", and §5.5 makes connection state the one thing the
 * UI must never misreport. Keeping it out of the Material roles means no
 * component picks it up by accident.
 */
@Immutable
data class SubspaceColors(
    val connected: Color,
    val connectedContainer: Color,
    val onConnected: Color,
    val pop: Color,
    val onPop: Color,
)

val LocalSubspaceColors = staticCompositionLocalOf<SubspaceColors> {
    error("SubspaceColors requested outside SubspaceTheme")
}
