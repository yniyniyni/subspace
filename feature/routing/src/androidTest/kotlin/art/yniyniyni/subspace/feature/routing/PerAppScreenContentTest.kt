// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import art.yniyniyni.subspace.core.model.PerAppMode
import org.junit.Rule
import org.junit.Test

/**
 * Layout and rendering facts a JVM state test cannot see — same reasoning as
 * [RoutingListScreenContentTest], including its note that no device is reachable
 * in the authoring environment.
 *
 * Animations must be off and the screen unlocked (§11) or these fail
 * non-deterministically and look like product bugs.
 */
class PerAppScreenContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val noopActions =
        PerAppActions(
            onSetMode = {},
            onToggle = {},
            onSearch = {},
            onSave = {},
            onDiscard = {},
            onBack = {},
        )

    private val rows =
        listOf(
            AppRow("com.example.bank", "Bank", isSelected = true),
            AppRow("com.example.maps", "Maps", isSelected = false),
        )

    @Test
    fun everyRowRendersItsLabel() {
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows), noopActions)
        }

        rule.onNodeWithText("Bank").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
    }

    // §7.2: shown and flagged, never silently dropped.
    @Test
    fun anUninstalledSelectionIsMarkedRatherThanHidden() {
        val ghost = AppRow("com.example.deleted", "com.example.deleted", isSelected = true, isInstalled = false)
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows + ghost), noopActions)
        }

        rule.onNodeWithText("com.example.deleted").assertIsDisplayed()
        rule.onNodeWithText("Not installed").assertIsDisplayed()
    }

    // The warning is the UI half of §6.3's guard. Save must be unavailable, not
    // merely discouraged.
    @Test
    fun anEmptyAllowListWarnsAndBlocksSave() {
        val unselected = rows.map { it.copy(isSelected = false) }
        rule.setContent {
            PerAppScreenContent(
                PerAppState(mode = PerAppMode.AllowList, rows = unselected, isDirty = true),
                noopActions,
            )
        }

        val emptyAllowListMessage =
            "Select at least one app, or switch the mode off. " +
                "A tunnel no app can use looks connected but carries nothing."
        rule.onNodeWithText(emptyAllowListMessage).assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    // Spec §7.3: the user is told before committing, not after the tunnel drops.
    // isTunnelActive covers Connecting as well as Connected — the notice has to
    // reach the cold-start window, which is where a save is most likely to
    // surprise someone.
    @Test
    fun anActiveTunnelIsAnnouncedBeforeSaving() {
        rule.setContent {
            PerAppScreenContent(
                PerAppState(mode = PerAppMode.DenyList, rows = rows, isDirty = true, isTunnelActive = true),
                noopActions,
            )
        }

        rule.onNodeWithText("Saving will reconnect the tunnel.").assertIsDisplayed()
    }

    // The count is the whole selection, not the visible slice of it: a query that
    // hides the ticked rows must not read as "0 apps selected".
    @Test
    fun theCountReportsTheSelectionRatherThanTheFilteredRows() {
        rule.setContent {
            PerAppScreenContent(
                PerAppState(
                    mode = PerAppMode.DenyList,
                    rows = emptyList(),
                    selectedCount = 2,
                    query = "zzzz",
                ),
                noopActions,
            )
        }

        rule.onNodeWithText("2 apps selected").assertIsDisplayed()
    }

    @Test
    fun aCleanDraftCannotBeSaved() {
        rule.setContent {
            PerAppScreenContent(PerAppState(mode = PerAppMode.DenyList, rows = rows, isDirty = false), noopActions)
        }

        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun offModeStillRendersTheModeChoices() {
        rule.setContent { PerAppScreenContent(PerAppState(rows = rows), noopActions) }

        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("Only selected apps").assertIsDisplayed()
        rule.onNodeWithText("All except selected").assertIsDisplayed()
    }
}
