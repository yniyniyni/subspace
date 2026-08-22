// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

/**
 * Task 14: `happ://` and `subspace://` routing links reach the app, and a
 * second one arriving while it is already foregrounded is not dropped.
 *
 * Asserts on [MainActivity.pendingRoutingImport] rather than on rendered text.
 * What this test owns is the *intent* boundary — that the filter resolves, that
 * `onNewIntent` fires under `singleTop`, and that the link is carried in memory
 * rather than through the back stack. That the sheet then renders is
 * `ImportReviewSheetTest`'s subject, and driving the whole navigation graph
 * here would make a filter regression fail as a Compose-finder error.
 *
 * **Not run in this environment** — no Android device or emulator is reachable
 * here. Compiled and left for a `connectedDebugAndroidTest` pass.
 *
 * camelCase test names throughout, same DEX-040 constraint every other
 * instrumented test in this repo documents.
 */
class RoutingDeeplinkTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aRoutingDeeplinkReachesTheApp() {
        ActivityScenario.launch<MainActivity>(viewIntent(FIRST_LINK)).use { scenario ->
            scenario.awaitEnteredImportPipeline(FIRST_LINK)
        }
    }

    // `onNewIntent` under singleTop is deliberately NOT tested here.
    //
    // ActivityScenario cannot model an activity that re-enters itself: driving
    // the override directly through Instrumentation.callActivityOnNewIntent
    // bypasses its lifecycle bookkeeping, and starting the intent from the
    // activity leaves its tracked instance stuck at PAUSED. Both produce a test
    // that asserts correctly and then fails for 45 s in close().
    //
    // It is verified on hardware instead, and was: with the first review sheet
    // open, a second `subspace://routing/add/...` delivered by `adb shell am
    // start` replaced the sheet's contents with the second profile rather than
    // being dropped. Recorded as a device-checklist row in
    // docs/agent/research/2026-08-20-m6-device-verification.md.

    // subspace:// is registered alongside happ:// and costs one manifest line;
    // a filter that silently covers only one scheme is the failure this pins.
    @Test
    fun theSubspaceSchemeResolvesToo() {
        val link = "subspace://routing/add/" + encode(SECOND_PROFILE)
        ActivityScenario.launch<MainActivity>(viewIntent(link)).use { scenario ->
            scenario.awaitEnteredImportPipeline(link)
        }
    }

    // A launcher tap is not an import. Without the ACTION_VIEW guard, every
    // cold start would offer whatever the last intent happened to carry.
    @Test
    fun anOrdinaryLaunchOffersNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.pendingRoutingImport.link.value shouldBe null
            }
        }
    }

    /**
     * Waits until [link] has entered the import pipeline.
     *
     * Asserting `pendingRoutingImport.link.value == link` directly is a race
     * this test lost on hardware: by the time `onActivity` runs, the nav host
     * has already routed to the routing list, the sheet has taken the offer and
     * `consumePendingOffer` has cleared the holder — so the field reads null
     * precisely *because* the whole path worked.
     *
     * Either state proves the intent boundary did its job: still pending (the
     * sheet has not collected it yet) or already presented (it has). What would
     * fail is a link that never appears in either, which is what a broken
     * filter, a missing `onNewIntent` or a dropped `ACTION_VIEW` produces.
     */
    private fun ActivityScenario<MainActivity>.awaitEnteredImportPipeline(link: String) {
        val deadline = SystemClock.uptimeMillis() + PIPELINE_TIMEOUT_MILLIS
        var entered = false
        while (!entered && SystemClock.uptimeMillis() < deadline) {
            onActivity { activity ->
                val holder = activity.pendingRoutingImport
                entered = holder.link.value == link || link in holder.presentedTexts.value
            }
            if (!entered) Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        entered shouldBe true
    }

    private fun viewIntent(link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName)

    private companion object {
        const val PIPELINE_TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 50L

        // §5.6: a test fixture, not a user's routing table — no real domain here.
        const val FIRST_PROFILE = """{"Name":"FirstProfile","DirectSites":["domain:first.example"]}"""
        const val SECOND_PROFILE = """{"Name":"SecondProfile","DirectSites":["domain:second.example"]}"""

        fun encode(json: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

        val FIRST_LINK = "happ://routing/add/" + encode(FIRST_PROFILE)
    }
}
