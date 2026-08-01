// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import art.yniyniyni.subspace.core.ui.R
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

// Backtick names with spaces are avoided here (unlike the task brief's literal
// text) for the same reason ConnectControlTest.kt gives: this module's minSdk
// (26, from subspace.android.library) makes D8 emit a DEX version that
// rejects spaces in synthetic class names, and any lambda passed to a test
// method inherits that method's JVM name. camelCase throughout.
class FloatingNavigationBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onlyTheSelectedDestinationShowsItsLabel() {
        composeRule.setContent {
            SubspaceTheme {
                FloatingNavigationBar(items = threeItems(), selected = "servers", onSelect = {})
            }
        }
        composeRule.onNodeWithText("Servers").assertExists()
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
    }

    // Labels are hidden for unselected items, so the content description is
    // the only accessible name they have.
    @Test
    fun everyDestinationIsReachableByContentDescription() {
        composeRule.setContent {
            SubspaceTheme {
                FloatingNavigationBar(items = threeItems(), selected = "servers", onSelect = {})
            }
        }
        composeRule.onNodeWithContentDescription("Connect").assertExists()
        composeRule.onNodeWithContentDescription("Settings").assertExists()
    }

    // Beyond the brief's two required tests: the whole point of exposing
    // NavItem.value through onSelect is that a screen reader user tapping the
    // content-description-only "Settings" node (above) actually navigates —
    // not just that the node exists.
    @Test
    fun tappingAnUnselectedDestinationReportsItsValue() {
        var selectedValue: String? = null
        composeRule.setContent {
            SubspaceTheme {
                FloatingNavigationBar(
                    items = threeItems(),
                    selected = "servers",
                    onSelect = { selectedValue = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        selectedValue shouldBe "settings"
    }

    private fun threeItems(): List<NavItem> =
        listOf(
            NavItem(value = "connect", labelRes = R.string.nav_item_connect, icon = testIcon()),
            NavItem(value = "servers", labelRes = R.string.nav_item_servers, icon = testIcon()),
            NavItem(value = "settings", labelRes = R.string.nav_item_settings, icon = testIcon()),
        )

    // A minimal real ImageVector, built by hand rather than pulling in
    // material-icons-core: NavItem.icon only needs *a* vector at the type
    // level for this test, and the actual iconography is a caller decision
    // (real screens in Task 16+) this module has no stake in.
    private fun testIcon(): ImageVector =
        ImageVector
            .Builder(
                name = "TestIcon",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(0f, 0f)
                lineTo(24f, 0f)
                lineTo(24f, 24f)
                lineTo(0f, 24f)
                close()
            }.build()
}
