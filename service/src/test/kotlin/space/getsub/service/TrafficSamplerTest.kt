// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.core.model.TagTraffic

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
        // Documentary, not a guard (M10): subsumed by the exact assertEquals
        // above — any value this assertion would catch already fails there.
        // Kept because it states the property in the reader's own terms
        // ("nowhere near the observed 8.0 GB defect, let alone 4 GiB"), not
        // because it discriminates anything the line above does not.
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
        // Documentary, not a guard (M10): subsumed by the exact assertEquals
        // above (uplinkBytes == postWrap makes this bound trivially true).
        // Kept because it states the under-count bound in the reader's own
        // terms, not because it discriminates anything the line above does not.
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
        // Documentary, not a guard (M10): subsumed by the two exact
        // assertEquals calls above (1_000L is already >= 0). Kept because it
        // states the property this test is named for — the total never goes
        // negative — not because it discriminates anything the lines above
        // do not.
        assertEquals(true, sample.uplinkBytes >= 0)
        assertEquals(true, sample.downlinkBytes >= 0)
    }

    // --- Per-tag breakdown (review finding I1) ---
    //
    // Before the fix, TrafficSamplerLoop.copy()'d a raw current reading from
    // xray's /debug/vars onto the sample, bypassing TrafficSampler entirely.
    // xray is restarted by a retained-TUN restart exactly like tun2socks is,
    // so its per-tag counters reset to zero on every Wi-Fi<->cellular
    // handoff, while the session total keeps accumulating -- the rows and the
    // tile they decompose disagreed permanently from the first handoff
    // onward. These tests exercise TrafficSampler.accept's own per-tag
    // accumulation, which is where that decision now has to live.

    @Test
    fun `per-tag traffic accumulates across a simulated xray restart instead of resetting`() {
        val sampler = TrafficSampler()
        val preRestart = 9_000_000L // ~9 MB moved by "proxy" before the handoff
        val postRestart = 500L // a little more after xray comes back up

        // xray's counter for this tag ramps up pre-handoff...
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 0, 0)))
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", preRestart, preRestart)))
        // ...then a retained-TUN restart brings xray back with its stats
        // reset to near zero, the same way the raw hev counters reset.
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", postRestart, postRestart)))

        val row = sample.perTag.single { it.tag == "proxy" }
        val expected = preRestart + postRestart
        assertEquals(expected, row.uplinkBytes)
        assertEquals(expected, row.downlinkBytes)
        // The device defect this reproduces: without accumulation the reading
        // simply falls back to postRestart, discarding everything moved
        // before the restart.
        assertTrue(row.uplinkBytes > postRestart)
    }

    @Test
    fun `a newly seen tag's first reading is counted in full, not baselined`() {
        val sampler = TrafficSampler()
        // Unlike the session totals, a tag's first reading is not a baseline:
        // xray's lifetime is bounded by the session, so the first reading a
        // tag is ever seen at IS this session's traffic for that tag so far.
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("direct", 4_000L, 6_000L)))

        val row = sample.perTag.single { it.tag == "direct" }
        assertEquals(4_000L, row.uplinkBytes)
        assertEquals(6_000L, row.downlinkBytes)
    }

    @Test
    fun `a tag that disappears keeps its accumulated total and stops growing`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 1_000L, 2_000L), TagTraffic("direct", 500L, 500L)))
        // "direct" drops out of this tick's payload -- xray restarted with a
        // config that no longer names it, say -- while "proxy" keeps moving.
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 1_500L, 2_500L)))

        val direct = sample.perTag.single { it.tag == "direct" }
        assertEquals(500L, direct.uplinkBytes)
        assertEquals(500L, direct.downlinkBytes)
        val proxy = sample.perTag.single { it.tag == "proxy" }
        assertEquals(1_500L, proxy.uplinkBytes)
        assertEquals(2_500L, proxy.downlinkBytes)
        // The disappeared row is retained, not dropped.
        assertEquals(setOf("proxy", "direct"), sample.perTag.map { it.tag }.toSet())
    }

    @Test
    fun `the sum of per-tag downlink tracks the session total's shape across a restart`() {
        val sampler = TrafficSampler()
        // TUN-level counters include IP/TCP headers the xray payload counters
        // do not, so the two are never expected to be equal -- only to move
        // together. Baseline tick establishes both at zero traffic so far.
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 0, 0)))

        val beforeRestart = sampler.accept(reading(1_000, 9_000_000), listOf(TagTraffic("proxy", 900, 8_800_000)))
        val beforeSumDown = beforeRestart.perTag.sumOf { it.downlinkBytes }
        assertTrue(beforeSumDown > 0)
        assertTrue(beforeSumDown <= beforeRestart.downlinkBytes)

        // A retained-TUN restart: both the hev totals' underlying counter and
        // xray's per-tag counter fall, in the same tick, because the same
        // network handoff restarted both processes.
        val afterRestart = sampler.accept(reading(1_050, 9_012_345), listOf(TagTraffic("proxy", 50, 12_000)))
        val afterSumDown = afterRestart.perTag.sumOf { it.downlinkBytes }

        // Both figures kept climbing across the restart -- neither collapsed
        // back toward zero the way the pre-fix per-tag reading did.
        assertTrue(afterSumDown > beforeSumDown)
        assertTrue(afterRestart.downlinkBytes > beforeRestart.downlinkBytes)
        assertTrue(afterSumDown <= afterRestart.downlinkBytes)
    }

    // --- Explicit epoch signalling (F4 / ruling R38, amending R28) ---
    //
    // TrafficSamplerLoop no longer leaves a restart for delta()'s falling-reading rule to infer:
    // TunnelService.restartCoreRetainingTun now calls TrafficSamplerLoop.notifyCountersRestarted(),
    // which calls TrafficSampler.beginNewEpoch() before the new epoch's first reading is folded
    // in. These tests drive beginNewEpoch() directly -- the reviewer's own probe values
    // ("before=100, new epoch raw=150, observed=150, expected=250") are reproduced as the "above"
    // case below. Without beginNewEpoch() actually resetting the "previous reading" baseline (i.e.
    // if it were a no-op, or if it called the deleted whole-session reset() instead), every one of
    // these would fail: a no-op reproduces the exact review bug (falls through to delta(), which
    // undercounts or zeroes the below/equal cases and is simply wrong for "above"); a whole-session
    // reset would zero the accumulated totals these tests assert are preserved.

    @Test
    fun `an explicit epoch signal counts a lower new-epoch reading in full, not by delta`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0))
        sampler.accept(reading(100, 100)) // pre-restart: accumulated = 100

        sampler.beginNewEpoch() // TunnelService just restarted hev between polls
        val sample = sampler.accept(reading(60, 60)) // new epoch's first reading, below the old prev

        // 100 (preserved) + 60 (counted in full) = 160. Falling back to delta() here would treat
        // 60 < 100 as a fresh epoch too and happen to land on the same number by coincidence --
        // the "equal"/"above" cases below are what actually discriminate the fix from delta().
        assertEquals(160L, sample.uplinkBytes)
        assertEquals(160L, sample.downlinkBytes)
    }

    @Test
    fun `an explicit epoch signal counts a new-epoch reading equal to the old prev in full`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0))
        sampler.accept(reading(100, 100)) // pre-restart: accumulated = 100

        sampler.beginNewEpoch()
        // The equality case review finding F4 calls out as "worse": delta(100, 100) = 0, silently
        // discarding the new epoch's traffic entirely.
        val sample = sampler.accept(reading(100, 100))

        assertEquals(200L, sample.uplinkBytes)
        assertEquals(200L, sample.downlinkBytes)
    }

    @Test
    fun `an explicit epoch signal counts a higher new-epoch reading in full without subtracting the old epoch`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0))
        sampler.accept(reading(100, 100)) // pre-restart: accumulated = 100, matches the reviewer's probe

        sampler.beginNewEpoch()
        // The reviewer's own probe: "before=100, new epoch raw=150, observed=150, expected=250".
        // delta(100, 150) = 50, which is what the old inference-only code returned.
        val sample = sampler.accept(reading(150, 150))

        assertEquals(250L, sample.uplinkBytes)
        assertEquals(250L, sample.downlinkBytes)
    }

    @Test
    fun `an explicit epoch signal counts a lower new-epoch tag reading in full, not by delta`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 0, 0)))
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 100, 100)))

        sampler.beginNewEpoch()
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 60, 60)))

        val row = sample.perTag.single { it.tag == "proxy" }
        assertEquals(160L, row.uplinkBytes)
        assertEquals(160L, row.downlinkBytes)
    }

    @Test
    fun `an explicit epoch signal counts a new-epoch tag reading equal to the old prev in full`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 0, 0)))
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 100, 100)))

        sampler.beginNewEpoch()
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 100, 100)))

        val row = sample.perTag.single { it.tag == "proxy" }
        assertEquals(200L, row.uplinkBytes)
        assertEquals(200L, row.downlinkBytes)
    }

    @Test
    fun `an explicit epoch signal counts a higher new-epoch tag reading in full without subtracting the old epoch`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 0, 0)))
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 100, 100)))

        sampler.beginNewEpoch()
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 150, 150)))

        val row = sample.perTag.single { it.tag == "proxy" }
        assertEquals(250L, row.uplinkBytes)
        assertEquals(250L, row.downlinkBytes)
    }

    @Test
    fun `an explicit epoch signal leaves an unrelated tag's accumulator untouched`() {
        val sampler = TrafficSampler()
        sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 100, 100), TagTraffic("direct", 40, 40)))

        sampler.beginNewEpoch()
        // Only "proxy" reappears this tick -- "direct" is simply absent, the same as the ordinary
        // disappearing-tag case, and must not be zeroed or otherwise disturbed by the epoch reset.
        val sample = sampler.accept(reading(0, 0), listOf(TagTraffic("proxy", 30, 30)))

        val proxy = sample.perTag.single { it.tag == "proxy" }
        assertEquals(130L, proxy.uplinkBytes)
        val direct = sample.perTag.single { it.tag == "direct" }
        assertEquals(40L, direct.uplinkBytes)
    }
}
