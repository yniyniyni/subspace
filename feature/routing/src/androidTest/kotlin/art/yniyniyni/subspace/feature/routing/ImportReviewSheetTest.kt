// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsState
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
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
            dns =
            ProfileDns(
                remote = DnsResolver(DnsTransport.DOH, domain = "https://dns.example/dns-query"),
                domestic = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1"),
                hosts = mapOf("dns.example" to "1.1.1.1", "local.example" to "10.0.0.1"),
                fakeDns = true,
            ),
            dnsState = DnsState.Applied,
            willActivate = true,
        )

    // F8: a confirmed import that fails is returned, not thrown. Closing the
    // sheet as if it had succeeded left the user with no reason and no retry.
    @Test
    fun aFailedImportNamesItsReasonAndOffersRetry() {
        setContent(
            reviewingState.copy(stage = Stage.Failed, failure = RuleSetAssetFailure.TimedOut),
        )

        composeRule.onNodeWithText("The download took too long and was stopped.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    // Device run 2026-08-22 item 6: replacing the profile that is currently
    // routing traffic overwrites the rules in force, so the stored-only wording
    // was a false reassurance on exactly the import that changes most.
    @Test
    fun replacingTheActiveProfileSaysTheRulesTakeEffectNow() {
        setContent(
            reviewingState.copy(
                willActivate = false,
                replacesExisting = true,
                replacesActive = true,
            ),
        )

        composeRule
            .onNodeWithText("These rules are the ones currently in use, so they take effect right away.")
            .assertIsDisplayed()
    }

    @Test
    fun replacingANonActiveProfileStillSaysTheActiveRulesAreUntouched() {
        setContent(reviewingState.copy(willActivate = false, replacesExisting = true, replacesActive = false))

        composeRule
            .onNodeWithText("This profile will be stored and will not replace the active rules.")
            .assertIsDisplayed()
    }

    // F9: while Applying this sheet blocks swipe, scrim and back, and the row's
    // own cancel sits behind it — so this must be the reachable one.
    @Test
    fun anApplyingImportCanStillBeCancelled() {
        setContent(reviewingState.copy(stage = Stage.Applying))

        composeRule.onNodeWithText("Stop download").assertIsDisplayed()
    }

    private fun setContent(state: ImportReviewState = reviewingState) {
        composeRule.setContent {
            SubspaceTheme {
                ImportReviewSheetContent(
                    state = state,
                    actions = ImportReviewActions(onConfirm = {}, onDismiss = {}),
                )
            }
        }
    }

    @Test
    fun theDnsDisclosureShowsResolverContentsHostsAndFakeDns() {
        setContent()

        composeRule.onNodeWithText("Remote DNS: DoH https://dns.example/dns-query").assertIsDisplayed()
        composeRule.onNodeWithText("Domestic DNS: DoU 1.1.1.1").assertIsDisplayed()
        composeRule.onNodeWithText("2 custom host mappings").assertIsDisplayed()
        composeRule.onNodeWithText("Uses FakeDNS").assertIsDisplayed()
    }

    @Test
    fun anInvalidDnsDisclosureUsesTheSafeFallbackCopy() {
        setContent(reviewingState.copy(dns = ProfileDns.INVALID, dnsState = DnsState.Invalid))

        composeRule
            .onNodeWithText("This profile's DNS settings could not be read. Your own DNS setting will be used.")
            .assertIsDisplayed()
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
        composeRule
            .onNodeWithText(
                string(
                    R.string.import_review_dns_remote,
                    "DoH",
                    "https://dns.example/dns-query",
                ),
            ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.import_review_will_activate)).assertIsDisplayed()
    }

    @Test
    fun confirmIsNotTheDefaultFocusedAction() {
        setContent()

        composeRule.onNodeWithText(string(R.string.import_review_confirm)).assertIsNotFocused()
    }

    // Was `applyingDisablesCancelAndConfirm`, which asserted that Applying left
    // both buttons disabled. That was the defect: this sheet also blocks swipe,
    // scrim and system-back while Applying, and the only cancel-download control
    // lived on the routing row behind it — so a confirmed import could not be
    // stopped at all. Confirm is still not offered (it is already running); what
    // replaces the pair is a reachable stop.
    @Test
    fun applyingOffersAStopAndNotAConfirm() {
        setContent(reviewingState.copy(stage = Stage.Applying))

        composeRule.onNodeWithText(string(R.string.import_review_cancel_download)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.import_review_confirm)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.import_review_dismiss)).assertDoesNotExist()
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
