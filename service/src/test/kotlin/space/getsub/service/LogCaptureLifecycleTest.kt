// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.service.log.TeardownStep
import space.getsub.service.log.teardownOrder

/**
 * Guards [teardownOrder] — the schedule `TunnelService.stopTunnel` actually
 * executes (see that function's call site and `TeardownOrder.kt`'s KDoc).
 * Dropping a step from that list compiles cleanly, because
 * `runTeardownStep`'s `when` stays exhaustive over the enum regardless of
 * what `teardownOrder()` returns — so completeness has to be asserted here,
 * not left to the compiler. A version of this file that only checked
 * relative ordering passed unchanged with [TeardownStep.CloseTun] deleted
 * from the list (review finding I-3): `indexOf` returns -1 for a missing
 * element, and `-1 < stopCaptureIndex` is `true`, so "precedes" was being
 * asserted with a predicate a missing element satisfies.
 */
class LogCaptureLifecycleTest {
    @Test
    fun `every declared teardown step is scheduled exactly once`() {
        val order = teardownOrder()

        assertEquals(
            "teardownOrder() must schedule every TeardownStep the enum declares — a step " +
                "missing here still compiles, because runTeardownStep's `when` stays " +
                "exhaustive over the enum independent of what this list contains",
            TeardownStep.entries.toSet(),
            order.toSet(),
        )
        assertEquals(
            "teardownOrder() must not schedule any step twice — Tun2Socks.stop() is " +
                "idempotent but fd.close() is not",
            order.size,
            order.toSet().size,
        )
    }

    @Test
    fun `log capture stops after every phase that logs`() {
        val order = teardownOrder()
        val stopCapture = order.indexOf(TeardownStep.StopLogCapture)

        assertTrue("StopLogCapture must exist in the order", stopCapture >= 0)
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
        assertTrue("StopLogCapture must exist in the order", stopCapture >= 0)

        listOf(
            TeardownStep.StopCore,
            TeardownStep.StopTun2Socks,
            TeardownStep.CloseTun,
        ).forEach { phase ->
            val index = order.indexOf(phase)
            assertTrue("$phase must be present in the order", index >= 0)
            assertTrue("$phase must precede StopLogCapture", index < stopCapture)
        }
    }

    @Test
    fun `StopTrafficSampler sits immediately before StopLogCapture, not any other position`() {
        // TeardownStep.StopTrafficSampler's own KDoc requires this exact adjacency
        // (spec §1.5): the sampler must stop after the session's final state has
        // published and before the capture that must outlive it — and nowhere else.
        val order = teardownOrder()
        val samplerIndex = order.indexOf(TeardownStep.StopTrafficSampler)
        val captureIndex = order.indexOf(TeardownStep.StopLogCapture)

        assertTrue("StopTrafficSampler must be present in the order", samplerIndex >= 0)
        assertTrue("StopLogCapture must be present in the order", captureIndex >= 0)
        assertEquals(
            "StopTrafficSampler must sit immediately before StopLogCapture",
            captureIndex - 1,
            samplerIndex,
        )
    }
}
