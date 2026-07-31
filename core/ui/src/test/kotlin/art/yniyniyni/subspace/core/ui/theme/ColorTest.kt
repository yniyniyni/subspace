// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.theme

import androidx.compose.ui.graphics.Color
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2 relative-luminance contrast ratio between two opaque sRGB colors.
 * https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 */
private fun contrastRatio(
    a: Color,
    b: Color,
): Double {
    fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun relativeLuminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

class ColorTest {
    @Test
    fun `connected is tertiary-toned in both schemes`() {
        // The DS reserves --color-connected for an established tunnel. It must be
        // visually distinct from primary, or "connected" and "a button" look alike.
        subspaceLightColors.connected shouldNotBe SubspaceLightColorScheme.primary
        subspaceDarkColors.connected shouldNotBe SubspaceDarkColorScheme.primary
    }

    @Test
    fun `on-connected contrasts with connected in both schemes`() {
        contrastRatio(subspaceLightColors.connected, subspaceLightColors.onConnected) shouldBeGreaterThan 4.5
        contrastRatio(subspaceDarkColors.connected, subspaceDarkColors.onConnected) shouldBeGreaterThan 4.5
    }
}
