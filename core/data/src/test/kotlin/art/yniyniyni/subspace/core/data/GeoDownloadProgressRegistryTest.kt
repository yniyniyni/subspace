// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.coroutines.coroutineContext

/**
 * Task 13: the routing list renders `Downloading 12 MB / 23 MB` with a cancel,
 * so an in-flight generation needs a byte count the UI can read and a handle it
 * can stop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GeoDownloadProgressRegistryTest {
    private val registry = GeoDownloadProgressRegistry()

    @Test
    fun aRuleSetWithNothingInFlightReportsNoProgress() = runTest {
        registry.progress.first()[7L] shouldBe null
    }

    @Test
    fun theRunningCountAndItsTotalAreBothVisible() = runTest {
        registry.report(7L, "geoip.dat", downloadedBytes = 12L, totalBytes = 23L)

        registry.progress.first()[7L] shouldBe GeoDownloadProgress("geoip.dat", 12L, 23L)
    }

    // A chunked response declares no length. The row must be able to tell
    // "unknown total" from "zero bytes total" — one is a spinner, the other is
    // a completed download of nothing.
    @Test
    fun anUndeclaredTotalStaysNullRatherThanBecomingZero() = runTest {
        registry.report(7L, "geoip.dat", downloadedBytes = 12L, totalBytes = null)

        registry.progress.first()[7L]?.totalBytes shouldBe null
    }

    @Test
    fun oneRuleSetsProgressDoesNotDisturbAnother() = runTest {
        registry.report(7L, "geoip.dat", 12L, 23L)
        registry.report(8L, "geosite.dat", 1L, 2L)

        registry.progress.first()[7L]?.downloadedBytes shouldBe 12L
        registry.progress.first()[8L]?.downloadedBytes shouldBe 1L
    }

    @Test
    fun aFinishedGenerationLeavesNoStaleBar() = runTest {
        registry.report(7L, "geoip.dat", 12L, 23L)
        registry.clear(7L)

        registry.progress.first()[7L] shouldBe null
    }

    @Test
    fun cancellingStopsTheCoroutineThatRegisteredItself() = runTest {
        val registered = CompletableDeferred<Unit>()
        val running =
            async {
                registry.track(7L) {
                    registered.complete(Unit)
                    CompletableDeferred<Unit>().await() // never completes; only cancel ends this
                }
            }
        registered.await()

        registry.cancel(7L)

        running.isCancelled shouldBe true
        registry.progress.first()[7L] shouldBe null
    }

    // Cancelling a rule set that finished a microsecond ago must not reach into
    // whatever coroutine happens to be running next.
    @Test
    fun cancellingAnUntrackedRuleSetIsANoOp() = runTest {
        val survived =
            async {
                withContext(coroutineContext) { registry.cancel(9L) }
                "alive"
            }

        survived.await() shouldBe "alive"
    }
}
