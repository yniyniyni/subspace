// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import org.junit.Rule
import org.junit.Test

/**
 * Spec §6's five disclosures, as rendered. [ImportReviewViewModelTest] is the
 * JVM proof that preview never fetches and confirm is the write; this is the
 * layout half no unit test can see.
 *
 * camelCase test names throughout, same DEX-040 constraint every other
 * instrumented test in this repo documents.
 */
class ImportReviewSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val reviewingState =
        ImportReviewState(
            stage = Stage.Reviewing,
            name = "RussiaInside",
            replacesExisting = true,
            bucketCounts =
            mapOf(
                RouteOutcome.BLOCK to 3,
                RouteOutcome.PROXY to 2,
                RouteOutcome.DIRECT to 1,
            ),
            defaultRouteIsDirect = true,
            geoDownloads =
            listOf(
                GeoDownloadPreview(
                    host = "example.test",
                    fileName = "geoip.dat",
                    approximateBytes = null,
                    alreadyOnDevice = false,
                ),
                GeoDownloadPreview(
                    host = "example.test",
                    fileName = "geosite.dat",
                    approximateBytes = null,
                    alreadyOnDevice = true,
                ),
            ),
            hasUnappliedDns = true,
            willActivate = true,
        )

    private fun setContent(state: ImportReviewState = reviewingState) {
        composeRule.setContent {
            SubspaceTheme {
                ImportReviewSheetContent(
                    state = state,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun theFiveDisclosuresRenderFromResources() {
        setContent()

        composeRule.onNodeWithText("RussiaInside").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_review_replaces_existing)).assertIsDisplayed()
        composeRule.onNodeWithText(bucketCount(R.string.rule_set_editor_outcome_block, 3)).assertIsDisplayed()
        composeRule.onNodeWithText(bucketCount(R.string.rule_set_editor_outcome_proxy, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(bucketCount(R.string.rule_set_editor_outcome_direct, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_review_default_direct)).assertIsDisplayed()
        composeRule.onAllNodesWithText("example.test", substring = true).assertCountEquals(2)
        composeRule.onNodeWithText(string(R.string.import_review_dns_unapplied)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_review_will_activate)).assertIsDisplayed()
    }

    @Test
    fun confirmIsNotTheDefaultFocusedAction() {
        setContent()

        composeRule.onNodeWithText(string(R.string.import_review_confirm)).assertIsNotFocused()
    }

    private fun string(
        id: Int,
        vararg formatArgs: Any,
    ): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return if (formatArgs.isEmpty()) {
            context.getString(id)
        } else {
            context.getString(id, *formatArgs)
        }
    }

    private fun bucketCount(
        outcomeId: Int,
        count: Int,
    ): String = string(R.string.import_review_bucket_count, string(outcomeId), count)
}
