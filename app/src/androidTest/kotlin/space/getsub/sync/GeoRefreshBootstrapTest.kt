// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.kotest.matchers.shouldBe
import org.junit.Test

private const val POLL_TIMEOUT_MILLIS = 5_000L
private const val POLL_INTERVAL_MILLIS = 100L

/**
 * Review round 2, Critical 2: `SubspaceApplication.onCreate()` is the only production caller that
 * can ever bring the `geo-refresh` periodic job into existence — `GeoRefreshWorker`'s own
 * `finally` re-enqueues it on every run, but nothing enqueues the *first* run without this. A
 * missing bootstrap call there means the whole chain is dead on a fresh install: no exception, no
 * log line, no geo database ever refreshes.
 *
 * Deliberately does **not** call `WorkManagerTestInitHelper.initializeTestWorkManager`. That would
 * swap in a fresh, empty test `WorkManager` instance, which defeats the point — this test needs to
 * observe whatever the *real* production `WorkManager` looked like after this app's own
 * `SubspaceApplication.onCreate()` actually ran, which instrumentation already did before any test
 * method here executes (this is the real app under test, not a harness around it).
 *
 * The bootstrap call runs on `applicationScope.launch` — fire-and-forget, not awaited by
 * `onCreate()` — so there is an inherent short race between app launch finishing and this
 * assertion running; polling for a bounded window is the best available mitigation without making
 * `onCreate()` itself block on I/O, which §5.3 forbids on the app's own launch path. No device or
 * emulator is reachable in this environment to confirm this does not flake in practice — see
 * `task-14-report.md`'s fix report for Finding 2.
 */
class GeoRefreshBootstrapTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theGeoRefreshJobExistsAfterAppLaunch() {
        val workManager = WorkManager.getInstance(context)
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS

        var found = workManager.getWorkInfosForUniqueWork(GEO_REFRESH_WORK_NAME).get().isNotEmpty()
        while (!found && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            found = workManager.getWorkInfosForUniqueWork(GEO_REFRESH_WORK_NAME).get().isNotEmpty()
        }

        found shouldBe true
    }
}
