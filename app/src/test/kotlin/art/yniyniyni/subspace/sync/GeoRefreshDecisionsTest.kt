// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import art.yniyniyni.subspace.core.data.GeoInstallRequest
import art.yniyniyni.subspace.core.model.GeoDataKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Covers [GeoRefreshDecisions] — the decision logic [GeoRefreshScheduler] delegates to — without
 * a [androidx.work.WorkManager], so it runs as a plain JVM test.
 *
 * [GeoRefreshSchedulerTest] (`androidTest`) pins the same three behaviours plus the two
 * constraint-setting ones only [GeoRefreshScheduler.reschedule] performs; those two need a real
 * `WorkManager` and stay instrumented-only. This class exists so the decisions
 * [GeoRefreshSchedulerTest] cannot be run in this environment — no device or emulator is
 * reachable here — are not left covered by an unrunnable test alone.
 */
class GeoRefreshDecisionsTest {
    @Test
    fun `a scheduled run skips a file that is not due`() =
        runTest {
            val installs = mutableListOf<String>()
            val decisions = GeoRefreshDecisions(dueFiles = { emptyList() }, install = { installs += it.fileName })

            decisions.refreshDue()

            installs shouldBe emptyList()
        }

    @Test
    fun `a scheduled run installs every due file, in order`() =
        runTest {
            val installs = mutableListOf<String>()
            val decisions =
                GeoRefreshDecisions(
                    dueFiles = { listOf(geoipRequest(), geositeRequest()) },
                    install = { installs += it.fileName },
                )

            decisions.refreshDue()

            installs shouldBe listOf("geoip.dat", "geosite.dat")
        }

    // §A.5: the cap is for the CDN's benefit, not a restriction on the owner.
    @Test
    fun `refreshNow installs even when nothing is due`() =
        runTest {
            val installs = mutableListOf<String>()
            val decisions = GeoRefreshDecisions(dueFiles = { emptyList() }, install = { installs += it.fileName })

            decisions.refreshNow(geoipRequest())

            installs shouldBe listOf("geoip.dat")
        }

    @Test
    fun `refreshNow never consults dueFiles`() =
        runTest {
            var dueFilesCalled = false
            val decisions =
                GeoRefreshDecisions(
                    dueFiles = {
                        dueFilesCalled = true
                        emptyList()
                    },
                    install = {},
                )

            decisions.refreshNow(geoipRequest())

            dueFilesCalled shouldBe false
        }

    private fun geoipRequest() =
        GeoInstallRequest(
            fileName = "geoip.dat",
            sourceUrl = "https://example.invalid/geoip.dat",
            geoType = GeoDataKind.IP,
        )

    private fun geositeRequest() =
        GeoInstallRequest(
            fileName = "geosite.dat",
            sourceUrl = "https://example.invalid/dlc.dat",
            geoType = GeoDataKind.DOMAIN,
        )
}
