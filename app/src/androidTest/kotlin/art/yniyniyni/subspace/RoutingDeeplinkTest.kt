// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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

    @Test
    fun recreationDoesNotReplayConsumedLaunchIntentButOnNewIntentDoes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val first =
            instrumentation.startActivitySync(
                viewIntent(FIRST_LINK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        first.pendingRoutingImport.consume(FIRST_LINK)
        val offers = Channel<String>(Channel.UNLIMITED)
        val collector = launch { first.pendingRoutingImport.link.filterNotNull().collect(offers::send) }
        val application = context.applicationContext as Application
        val recreationObserver = MainActivityRecreationObserver(first)
        application.registerActivityLifecycleCallbacks(recreationObserver)

        try {
            instrumentation.runOnMainSync(first::recreate)
            val recreated =
                checkNotNull(withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) { recreationObserver.resumed.receive() }) {
                    "MainActivity did not resume after recreation"
                }
            withTimeoutOrNull(NO_REPLAY_WINDOW_MILLIS) { offers.receive() } shouldBe null

            context.startActivity(
                viewIntent(FIRST_LINK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) { offers.receive() } shouldBe FIRST_LINK

            recreated.pendingRoutingImport.consume(FIRST_LINK)
            context.startActivity(
                viewIntent(FIRST_LINK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            withTimeoutOrNull(PIPELINE_TIMEOUT_MILLIS) { offers.receive() } shouldBe FIRST_LINK
            instrumentation.runOnMainSync(recreated::finish)
        } finally {
            application.unregisterActivityLifecycleCallbacks(recreationObserver)
            collector.cancel()
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
        const val NO_REPLAY_WINDOW_MILLIS = 500L

        // §5.6: a test fixture, not a user's routing table — no real domain here.
        const val FIRST_PROFILE = """{"Name":"FirstProfile","DirectSites":["domain:first.example"]}"""
        const val SECOND_PROFILE = """{"Name":"SecondProfile","DirectSites":["domain:second.example"]}"""

        fun encode(json: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

        val FIRST_LINK = "happ://routing/add/" + encode(FIRST_PROFILE)
    }

    private class MainActivityRecreationObserver(
        private val previous: MainActivity,
    ) : Application.ActivityLifecycleCallbacks {
        val resumed = Channel<MainActivity>(capacity = 1)

        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity && activity !== previous) resumed.trySend(activity)
        }

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
