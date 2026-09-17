// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.service.log.TeardownStep
import space.getsub.service.log.teardownOrder

class LogCaptureLifecycleTest {
    @Test
    fun `log capture stops after every phase that logs`() {
        val order = teardownOrder()
        val stopCapture = order.indexOf(TeardownStep.StopLogCapture)

        assertEquals("StopLogCapture must exist in the order", true, stopCapture >= 0)
        assertEquals(
            "StopLogCapture must be last — spec §3.3, or the capture ends " +
                "before the teardown phases it exists to record",
            order.size - 1,
            stopCapture,
        )
    }

    @Test
    fun `the phases W7 depends on are ordered before the capture stops`() {
        val order = teardownOrder()
        val stopCapture = order.indexOf(TeardownStep.StopLogCapture)
        listOf(
            TeardownStep.StopCore,
            TeardownStep.StopTun2Socks,
            TeardownStep.CloseTun,
        ).forEach { phase ->
            assertEquals("$phase must precede StopLogCapture", true, order.indexOf(phase) < stopCapture)
        }
    }
}
