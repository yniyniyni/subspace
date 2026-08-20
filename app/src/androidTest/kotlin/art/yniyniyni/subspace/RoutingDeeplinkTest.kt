// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
    fun aRoutingDeeplinkReachesTheAppAndStoresNothingYet() {
        ActivityScenario.launch<MainActivity>(viewIntent(FIRST_LINK)).use { scenario ->
            scenario.onActivity { activity ->
                // Held in memory, awaiting the review sheet — nothing is
                // applied by the intent itself (rule 1).
                activity.pendingRoutingImport.link.value shouldBe FIRST_LINK
            }
        }
    }

    // Without onNewIntent the second link is silently dropped: MainActivity is
    // singleTop, so onCreate does not run again and getIntent() still returns
    // the first link. This is the whole reason singleTop needs the override.
    @Test
    fun aSecondDeeplinkWhileRunningIsHandledByOnNewIntent() {
        ActivityScenario.launch<MainActivity>(viewIntent(FIRST_LINK)).use { scenario ->
            scenario.onActivity { activity ->
                activity.pendingRoutingImport.link.value shouldBe FIRST_LINK

                // callActivityOnNewIntent drives the real protected override.
                // Widening it to public just so a test could call it would
                // change production API for a test's convenience.
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(activity, viewIntent(SECOND_LINK))

                activity.pendingRoutingImport.link.value shouldBe SECOND_LINK
            }
        }
    }

    // subspace:// is registered alongside happ:// and costs one manifest line;
    // a filter that silently covers only one scheme is the failure this pins.
    @Test
    fun theSubspaceSchemeResolvesToo() {
        val link = "subspace://routing/add/" + encode(FIRST_PROFILE)
        ActivityScenario.launch<MainActivity>(viewIntent(link)).use { scenario ->
            scenario.onActivity { activity ->
                activity.pendingRoutingImport.link.value shouldBe link
            }
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

    private fun viewIntent(link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(context.packageName)

    private companion object {
        // §5.6: a test fixture, not a user's routing table — no real domain here.
        const val FIRST_PROFILE = """{"Name":"FirstProfile","DirectSites":["domain:first.example"]}"""
        const val SECOND_PROFILE = """{"Name":"SecondProfile","DirectSites":["domain:second.example"]}"""

        fun encode(json: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

        val FIRST_LINK = "happ://routing/add/" + encode(FIRST_PROFILE)
        val SECOND_LINK = "happ://routing/add/" + encode(SECOND_PROFILE)
    }
}
