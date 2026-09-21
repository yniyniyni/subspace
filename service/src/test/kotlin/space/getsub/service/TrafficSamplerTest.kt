// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSamplerTest {
    private fun reading(up: Long, down: Long) = TunnelCounters(up, down, 0, 0)
    private fun readingWithPackets(up: Long, down: Long, upPkts: Long, downPkts: Long) =
        TunnelCounters(up, down, upPkts, downPkts)

    @Test
    fun `the first reading is the baseline, not a spike`() {
        val sampler = TrafficSampler()
        // A session that starts against a non-zero counter must not report the
        // whole counter as this session's traffic.
        val sample = sampler.accept(reading(up = 5_000, down = 9_000))
        assertEquals(0L, sample.uplinkBytes)
        assertEquals(0L, sample.downlinkBytes)
    }

    @Test
    fun `accumulates deltas across readings`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0))
        sampler.accept(reading(100, 200))
        val sample = sampler.accept(reading(150, 260))

        assertEquals(150L, sample.uplinkBytes)
        assertEquals(260L, sample.downlinkBytes)
    }

    @Test
    fun `a retained-TUN restart on a 64-bit ABI does not inject 4 GiB`() {
        // Reproduces the Pixel 8 device defect: one Wi-Fi to cellular handoff
        // made Home read 8.0 GB down / 8.0 GB up when the real session
        // traffic was 63.6 MB / 554 KB. On arm64-v8a/x86_64, size_t is 64
        // bits, so these counters cannot wrap at 2^32 -- a retained-TUN
        // restart just resets them to near zero from whatever they held.
        val sampler = TrafficSampler()
        val preRestartUp = 554_000L // ~554 KB uplink, matches the device report
        val preRestartDown = 63_600_000L // ~63.6 MB downlink, matches the device report
        val postRestartUp = 900L // a little traffic since the restart
        val postRestartDown = 12_345L

        sampler.accept(reading(0, 0)) // baseline: sampler attaches at session start
        sampler.accept(reading(preRestartUp, preRestartDown)) // ramps up pre-handoff
        // Wi-Fi -> cellular: restartCoreRetainingTun resets the 64-bit
        // counters to near zero. curr < prev, but this is a fresh epoch, not
        // a 32-bit wrap -- prev is nowhere near 2^32.
        val sample = sampler.accept(reading(postRestartUp, postRestartDown))

        val expectedUp = preRestartUp + postRestartUp
        val expectedDown = preRestartDown + postRestartDown
        assertEquals(expectedUp, sample.uplinkBytes)
        assertEquals(expectedDown, sample.downlinkBytes)
        // Nowhere near the observed 8.0 GB defect, let alone 4 GiB.
        assertEquals(true, sample.downlinkBytes < 100_000_000L)
    }

    @Test
    fun `a 32-bit wrap under-counts by at most the pre-rollover remainder`() {
        val sampler = TrafficSampler()
        val justBelow = 4_294_967_000L // a little under 2^32
        val postWrap = 1_000L
        sampler.accept(reading(justBelow, 0))
        // hev's size_t wrapped on armeabi-v7a: the raw value fell. The real
        // wrap delta was (2^32 - justBelow) + postWrap, but a falling
        // reading is now always treated as a fresh epoch, so this counts
        // only postWrap and silently drops the (2^32 - justBelow) bytes
        // moved before the rollover -- bounded by one 1 s sampling interval
        // ([TrafficSamplerLoop]), never by 4 GiB.
        val sample = sampler.accept(reading(postWrap, 0))

        val preRolloverRemainder = (1L shl 32) - justBelow
        assertEquals(postWrap, sample.uplinkBytes)
        assertEquals(true, sample.uplinkBytes <= postWrap + preRolloverRemainder)
    }

    @Test
    fun `a falling reading from a moderate value yields curr, not inflated arithmetic`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(4_000_000_000L, 4_000_000_000L))
        val sample = sampler.accept(reading(1, 1))

        // Old wrap-inference code computed (2^32 - 4_000_000_000) + 1 =
        // 294_967_297 here. The fix returns curr as-is.
        assertEquals(1L, sample.uplinkBytes)
        assertEquals(1L, sample.downlinkBytes)
    }

    @Test
    fun `reset starts a new session at zero`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0))
        sampler.accept(reading(500, 500))
        sampler.reset()

        val sample = sampler.accept(reading(10_000, 10_000))
        assertEquals(0L, sample.uplinkBytes)
        assertEquals(0L, sample.downlinkBytes)
    }

    @Test
    fun `a flat counter accumulates nothing`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(100, 100))
        sampler.accept(reading(100, 100))
        val sample = sampler.accept(reading(100, 100))

        assertEquals(0L, sample.uplinkBytes)
        assertEquals(0L, sample.downlinkBytes)
    }

    @Test
    fun `a 32-bit wrap in packets under-counts the same way bytes do`() {
        val sampler = TrafficSampler()
        val justBelow = 4_294_967_000L // a little under 2^32
        sampler.accept(readingWithPackets(0, 0, justBelow, justBelow))
        // Packets wrapped too, and the same fresh-epoch treatment applies:
        // this counts only the post-wrap packets, under-counting by at most
        // (2^32 - justBelow), never adding a spurious 4 GiB-equivalent.
        val sample = sampler.accept(readingWithPackets(0, 0, 1_000, 2_000))

        assertEquals(1_000L, sample.uplinkPackets)
        assertEquals(2_000L, sample.downlinkPackets)
    }

    @Test
    fun `a falling reading from above 2 to the 32 still yields curr`() {
        val sampler = TrafficSampler()
        val aboveMax = 5_000_000_000L // above 2^32, only reachable on a 64-bit ABI
        sampler.accept(reading(aboveMax, aboveMax))
        // A retained-TUN restart reset the counter. Every falling reading is
        // treated as a fresh epoch now, so this is exactly right regardless
        // of how large prev was -- the new total is curr, and it is positive.
        val sample = sampler.accept(reading(1_000, 1_000))

        assertEquals(1_000L, sample.uplinkBytes)
        assertEquals(1_000L, sample.downlinkBytes)
        assertEquals(true, sample.uplinkBytes >= 0)
        assertEquals(true, sample.downlinkBytes >= 0)
    }
}
