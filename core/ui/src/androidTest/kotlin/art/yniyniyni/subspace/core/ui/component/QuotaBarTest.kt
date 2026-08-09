// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.ui.component

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import org.junit.Rule
import org.junit.Test

// camelCase throughout — same DEX-040 constraint GroupCardTest.kt documents
// in full.
class QuotaBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aBoundedQuotaRendersTheBarAndItsLabel() {
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(usedBytes = 1_073_741_824L, totalBytes = 5_368_709_120L)
            }
        }
        // 1_073_741_824 bytes = 1.0 GB, 5_368_709_120 bytes = 5.0 GB.
        composeRule.onNodeWithText("1.0 GB of 5.0 GB used").assertExists()
    }

    @Test
    fun totalZeroMeansUnlimitedAndRendersNothing() {
        // §A.1: total=0 is the documented "no cap" convention, not zero bytes
        // remaining — a bar reading 100% used on an unlimited plan is worse
        // than no bar.
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(usedBytes = 5L, totalBytes = 0L)
            }
        }
        composeRule.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    @Test
    fun aMissingTotalRendersNothing() {
        // "the provider did not say" must not fall back to any drawn bar.
        composeRule.setContent {
            SubspaceTheme {
                QuotaBar(usedBytes = 5L, totalBytes = null)
            }
        }
        composeRule.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    @Test
    fun groupCardWithNoQuotaBytesDrawsNoSubscriptionAffordances() {
        // A MANUAL group's call site never passes quotaUsedBytes/quotaTotalBytes,
        // so both default to null here too — GroupCard must draw nothing extra.
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(
                    name = "Local configs",
                    profileCount = 1,
                    expanded = false,
                    actions =
                    GroupCardActions(
                        onToggleExpand = {},
                        onRename = {},
                        onDelete = {},
                        onAddProfile = {},
                    ),
                ) {}
            }
        }
        composeRule.onNodeWithText("used", substring = true).assertDoesNotExist()
    }

    @Test
    fun groupCardWithAMeasurableQuotaRendersTheBar() {
        composeRule.setContent {
            SubspaceTheme {
                GroupCard(
                    name = "My subscription",
                    profileCount = 4,
                    expanded = false,
                    actions =
                    GroupCardActions(
                        onToggleExpand = {},
                        onRename = {},
                        onDelete = {},
                        onAddProfile = {},
                    ),
                    quotaUsedBytes = 1_073_741_824L,
                    quotaTotalBytes = 5_368_709_120L,
                ) {}
            }
        }
        composeRule.onNodeWithText("1.0 GB of 5.0 GB used").assertExists()
    }
}
