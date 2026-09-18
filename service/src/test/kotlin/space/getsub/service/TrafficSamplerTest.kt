// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficSamplerTest {
    private fun reading(up: Long, down: Long) = TunnelCounters(up, down, 0, 0)

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
    fun `a 32-bit wrap is treated as a wrap, not a reset`() {
        val sampler = TrafficSampler()
        val justBelow = 4_294_967_000L // a little under 2^32
        sampler.accept(reading(justBelow, 0))
        // hev's size_t wrapped on armeabi-v7a: the raw value fell, but real
        // traffic went UP. Spec §1.3.
        val sample = sampler.accept(reading(1_000, 0))

        val expected = (1L shl 32) - justBelow + 1_000L
        assertEquals(expected, sample.uplinkBytes)
    }

    @Test
    fun `a wrap never produces a negative total`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(4_000_000_000L, 4_000_000_000L))
        val sample = sampler.accept(reading(1, 1))

        assertEquals(true, sample.uplinkBytes >= 0)
        assertEquals(true, sample.downlinkBytes >= 0)
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
}
