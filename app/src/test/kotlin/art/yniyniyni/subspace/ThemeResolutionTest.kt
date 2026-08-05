// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import art.yniyniyni.subspace.core.data.ThemePreference
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The defect this task closes: [MainActivity] used to call `SubspaceTheme`
 * with no `darkTheme` argument at all, so the stored [ThemePreference] never
 * reached it and the app stayed on the system theme no matter what Settings
 * showed selected. [resolveDarkTheme] is the function that now stands
 * between the two — these cases are exactly the three radio options plus the
 * pre-first-emission `null`.
 */
class ThemeResolutionTest {
    @Test
    fun `Light always resolves to the light scheme, regardless of the system`() {
        resolveDarkTheme(ThemePreference.Light, systemInDarkTheme = true) shouldBe false
        resolveDarkTheme(ThemePreference.Light, systemInDarkTheme = false) shouldBe false
    }

    @Test
    fun `Dark always resolves to the dark scheme, regardless of the system`() {
        resolveDarkTheme(ThemePreference.Dark, systemInDarkTheme = true) shouldBe true
        resolveDarkTheme(ThemePreference.Dark, systemInDarkTheme = false) shouldBe true
    }

    @Test
    fun `System follows whatever the platform reports`() {
        resolveDarkTheme(ThemePreference.System, systemInDarkTheme = true) shouldBe true
        resolveDarkTheme(ThemePreference.System, systemInDarkTheme = false) shouldBe false
    }

    @Test
    fun `a null preference (before Room's first emission) follows the system`() {
        resolveDarkTheme(null, systemInDarkTheme = true) shouldBe true
        resolveDarkTheme(null, systemInDarkTheme = false) shouldBe false
    }
}
