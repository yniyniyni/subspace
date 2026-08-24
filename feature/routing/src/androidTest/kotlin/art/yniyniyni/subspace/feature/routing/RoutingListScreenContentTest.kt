// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import art.yniyniyni.subspace.core.model.DnsState
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import art.yniyniyni.subspace.core.ui.theme.SubspaceTheme
import org.junit.Rule
import org.junit.Test

/**
 * Task 15: proves the two activation-gate markers render distinctly (never
 * conflated — see [RuleSetRow]'s own KDoc) and that the empty state appears
 * with nothing stored. [RoutingViewModelTest] is the runnable proof that the
 * gate is *enforced*; this is the layout/rendering half no plain JVM test can
 * see, the same split [art.yniyniyni.subspace.feature.settings.SettingsHwidLayoutTest]
 * documents for the identical reason.
 *
 * **Not run in this environment** — no Android device or emulator is
 * reachable here (see the M5 Task 15 report). Compiled and left for a
 * `connectedDebugAndroidTest` pass on a real device or CI runner.
 *
 * camelCase test names throughout, same DEX-040 constraint every other
 * instrumented test in this repo documents.
 */
class RoutingListScreenContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val blockedRow =
        RuleSetRow(
            id = 1,
            name = "ads",
            entryCount = 3,
            isActive = false,
            missingGeoFiles = setOf("geosite.dat"),
        )
    private val failedButActivatableRow =
        RuleSetRow(
            id = 2,
            name = "lan",
            entryCount = 1,
            isActive = true,
            missingGeoFiles = emptySet(),
            hasFailedGeoUpdate = true,
        )

    private val providerRow =
        RuleSetRow(
            id = 3,
            name = "vpn",
            entryCount = 12,
            isActive = false,
            missingGeoFiles = emptySet(),
            sourceKind = RoutingSourceKind.Header,
            subscriptionName = "NameVPN",
            assetState = RuleSetAssetState.Ready,
            dnsState = DnsState.Applied,
        )
    private val downloadingRow =
        RuleSetRow(
            id = 4,
            name = "geo",
            entryCount = 2,
            isActive = false,
            missingGeoFiles = emptySet(),
            sourceKind = RoutingSourceKind.Deeplink,
            assetState = RuleSetAssetState.Pending,
            downloadProgress = GeoProgress(downloadedBytes = 12_000_000, totalBytes = 23_000_000),
        )
    private val timedOutRow =
        RuleSetRow(
            id = 5,
            name = "slow",
            entryCount = 1,
            isActive = false,
            missingGeoFiles = emptySet(),
            sourceKind = RoutingSourceKind.Deeplink,
            assetState = RuleSetAssetState.Failed,
            assetFailure = RuleSetAssetFailure.TimedOut,
        )

    private fun setContent(state: RoutingState) {
        composeRule.setContent {
            SubspaceTheme {
                RoutingListScreenContent(
                    state = state,
                    actions =
                    RoutingListActions(
                        onActivate = {},
                        onDelete = {},
                        onCreateRuleSet = {},
                        onEditRuleSet = {},
                        onBack = {},
                    ),
                )
            }
        }
    }

    @Test
    fun aRuleSetMissingGeoFilesShowsTheBlockingMarkerAndADisabledSelector() {
        setContent(RoutingState(ruleSets = listOf(blockedRow)))

        composeRule.onNodeWithText("Needs geo files: geosite.dat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Activate ads").assertIsNotEnabled()
    }

    // The point of this screen: a failed-update marker never disables the selector the way a
    // missing-geo-files marker does — conflating the two is the defect Task 15's brief calls out.
    @Test
    fun aFailedUpdateShowsItsOwnMarkerWithoutBlockingActivation() {
        setContent(RoutingState(ruleSets = listOf(failedButActivatableRow)))

        composeRule.onNodeWithText("Last update failed").assertIsDisplayed()
        composeRule.onNodeWithText("Needs geo files:", substring = true).assertDoesNotExist()
        // The half this test is named for but did not assert: a failed refresh
        // is a warning, not a gate. Without this line, making
        // hasFailedGeoUpdate disable the selector leaves the test green.
        // Fix round 2: this test renders failedButActivatableRow ("lan"), not
        // blockedRow ("ads") — the finder previously matched zero nodes.
        composeRule.onNodeWithContentDescription("Activate lan").assertIsEnabled()
    }

    @Test
    fun noRuleSetsShowsTheEmptyState() {
        setContent(RoutingState(ruleSets = emptyList()))

        composeRule.onNodeWithText("No rule sets yet").assertIsDisplayed()
    }

    // M6 Task 13 / spec §9. Each of these is something the user cannot learn
    // anywhere else on this screen.
    @Test
    fun anImportedProfileNamesItsProviderAndOffersDuplicateInPlaceOfEdit() {
        setContent(RoutingState(ruleSets = listOf(providerRow)))

        composeRule.onNodeWithText("From \"NameVPN\"").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Duplicate and edit vpn").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit vpn").assertDoesNotExist()
    }

    @Test
    fun aHandMadeSetSaysSoAndKeepsItsEditAffordance() {
        setContent(RoutingState(ruleSets = listOf(failedButActivatableRow)))

        composeRule.onNodeWithText("Made here").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit lan").assertIsDisplayed()
    }

    @Test
    fun aProfileWithValidDnsSaysItSetsDns() {
        setContent(RoutingState(ruleSets = listOf(providerRow)))

        composeRule.onNodeWithText("Sets DNS").assertIsDisplayed()
    }

    @Test
    fun aProfileWithInvalidDnsSaysItUsesTheDnsSetting() {
        setContent(RoutingState(ruleSets = listOf(providerRow.copy(dnsState = DnsState.Invalid))))

        composeRule.onNodeWithText("DNS block not understood — using your DNS setting").assertIsDisplayed()
    }

    @Test
    fun aProfileWithFakeDnsAndNoSniffingSaysItNeedsSniffing() {
        setContent(RoutingState(ruleSets = listOf(providerRow.copy(dnsState = DnsState.NeedsSniffing))))

        composeRule.onNodeWithText("Needs sniffing on for FakeDNS").assertIsDisplayed()
    }

    @Test
    fun aProfileWithoutDnsShowsNoDnsBadge() {
        setContent(RoutingState(ruleSets = listOf(providerRow.copy(dnsState = DnsState.None))))

        composeRule.onNodeWithText("Sets DNS").assertDoesNotExist()
        composeRule.onNodeWithText("DNS block not understood — using your DNS setting").assertDoesNotExist()
        composeRule.onNodeWithText("Needs sniffing on for FakeDNS").assertDoesNotExist()
    }

    // Three independent axes. A downloading row is not a failed one, and a
    // failed one is not a blocked one.
    @Test
    fun aRunningDownloadShowsBothCountsAndACancel() {
        setContent(RoutingState(ruleSets = listOf(downloadingRow)))

        composeRule.onNodeWithText("Downloading", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel download for geo").assertIsDisplayed()
    }

    @Test
    fun aFailedGenerationNamesItsReason() {
        setContent(RoutingState(ruleSets = listOf(timedOutRow)))

        composeRule.onNodeWithText("Update failed — timed out").assertIsDisplayed()
    }

    @Test
    fun theImportEntryPointsAreReachable() {
        setContent(RoutingState(ruleSets = emptyList()))

        composeRule.onNodeWithContentDescription("Import a routing profile").performClick()

        composeRule.onNodeWithText("Import from clipboard").assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR code").assertIsDisplayed()
    }

    // Found on hardware: a literal-only profile rendered "Geo files ready" —
    // about files it does not have, under a warning triangle. The asset-state
    // line belongs only to sets that actually reference a geo database.
    @Test
    fun aLiteralOnlyProfileSaysNothingAboutGeoFiles() {
        setContent(
            RoutingState(
                ruleSets =
                listOf(
                    providerRow.copy(assetState = RuleSetAssetState.Ready, requiresGeoFiles = false),
                ),
            ),
        )

        composeRule.onNodeWithText("Geo files ready").assertDoesNotExist()
    }

    @Test
    fun aGeoBackedProfileStillReportsItsAssetsAsReady() {
        setContent(
            RoutingState(
                ruleSets =
                listOf(
                    providerRow.copy(assetState = RuleSetAssetState.Ready, requiresGeoFiles = true),
                ),
            ),
        )

        composeRule.onNodeWithText("Geo files ready").assertIsDisplayed()
    }

    @Test
    fun theOffRowIsAlwaysPresent() {
        setContent(RoutingState(ruleSets = listOf(blockedRow)))

        composeRule.onNodeWithText("Off").assertIsDisplayed()
    }
}
