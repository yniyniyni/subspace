// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class GeoRefreshSchedulerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @Test
    fun aScheduledRunSkipsAFileThatIsNotDue() = runTest {
        val installs = mutableListOf<String>()
        val scheduler =
            GeoRefreshScheduler(
                workManager = WorkManager.getInstance(context),
                dueFiles = { emptyList() },
                install = { installs += it.fileName },
                onMetered = { false },
            )

        scheduler.refreshDue()

        installs shouldBe emptyList()
    }

    @Test
    fun aScheduledRunInstallsEveryDueFile() = runTest {
        val installs = mutableListOf<String>()
        val scheduler =
            GeoRefreshScheduler(
                workManager = WorkManager.getInstance(context),
                dueFiles = { listOf(geoipRequest(), geositeRequest()) },
                install = { installs += it.fileName },
                onMetered = { false },
            )

        scheduler.refreshDue()

        installs shouldBe listOf("geoip.dat", "geosite.dat")
    }

    // §A.5: the cap is for the CDN's benefit, not a restriction on the owner.
    @Test
    fun refreshNowRunsEvenWhenNothingIsDue() = runTest {
        val installs = mutableListOf<String>()
        val scheduler =
            GeoRefreshScheduler(
                workManager = WorkManager.getInstance(context),
                dueFiles = { emptyList() },
                install = { installs += it.fileName },
                onMetered = { false },
            )

        scheduler.refreshNow(geoipRequest())

        installs shouldBe listOf("geoip.dat")
    }

    @Test
    fun theScheduledWorkRequiresAnUnmeteredNetworkByDefault() = runTest {
        val scheduler =
            GeoRefreshScheduler(
                workManager = WorkManager.getInstance(context),
                dueFiles = { emptyList() },
                install = {},
                onMetered = { false },
            )

        scheduler.reschedule()

        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(GEO_REFRESH_WORK_NAME).get()
        info.single().constraints.requiredNetworkType shouldBe androidx.work.NetworkType.UNMETERED
    }

    @Test
    fun allowingMeteredRelaxesTheConstraint() = runTest {
        val scheduler =
            GeoRefreshScheduler(
                workManager = WorkManager.getInstance(context),
                dueFiles = { emptyList() },
                install = {},
                onMetered = { true },
            )

        scheduler.reschedule()

        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(GEO_REFRESH_WORK_NAME).get()
        info.single().constraints.requiredNetworkType shouldBe androidx.work.NetworkType.CONNECTED
    }

    private fun geoipRequest() =
        art.yniyniyni.subspace.core.data.GeoInstallRequest(
            fileName = "geoip.dat",
            sourceUrl = "https://example.invalid/geoip.dat",
            geoType = art.yniyniyni.subspace.core.model.GeoDataKind.IP,
        )

    private fun geositeRequest() =
        art.yniyniyni.subspace.core.data.GeoInstallRequest(
            fileName = "geosite.dat",
            sourceUrl = "https://example.invalid/dlc.dat",
            geoType = art.yniyniyni.subspace.core.model.GeoDataKind.DOMAIN,
        )
}
