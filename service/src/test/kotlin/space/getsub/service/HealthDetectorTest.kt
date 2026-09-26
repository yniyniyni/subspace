// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Health
import space.getsub.core.model.TrafficSample

/**
 * M8.5 spec §4.3 (amended). Every sequence is one sample per second. A "tick" is
 * one second's delta in each direction: (bytes, packets).
 */
class HealthDetectorTest {
    private class Feed(val detector: HealthDetector = HealthDetector()) {
        private var up = 0L
        private var upP = 0L
        private var down = 0L
        private var downP = 0L
        private var now = 0L
        var last: Health = detector.accept(sample(), now)
            private set

        private fun sample() = TrafficSample(up, down, upP, downP)

        fun tick(upBytes: Long, upPackets: Long, downBytes: Long, downPackets: Long): Health {
            up += upBytes
            upP += upPackets
            down += downBytes
            downP += downPackets
            now += 1_000
            last = detector.accept(sample(), now)
            return last
        }

        fun repeat(seconds: Int, upBytes: Long, upPackets: Long, downBytes: Long, downPackets: Long): Health {
            kotlin.repeat(seconds) { tick(upBytes, upPackets, downBytes, downPackets) }
            return last
        }
    }

    // A TCP flow to a dead server: the app sends a ClientHello (~600 B) plus ACKs;
    // lwIP answers with SYN-ACK/ACK/RST, all 40–60 B (spec §4.3's source reading).
    private val deadTcpUp = 700L to 3L
    private val deadTcpDown = 150L to 3L

    @Test
    fun `starts idle`() {
        Feed().last shouldBe Health.Idle
    }

    @Test
    fun `a dead server over tcp stalls after the window, although downlink bytes moved`() {
        val f = Feed()
        f.repeat(19, deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Idle
        f.repeat(2, deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Stalled
    }

    @Test
    fun `a dead server over quic stalls with downlink completely flat`() {
        val f = Feed()
        f.repeat(21, upBytes = 1_250, upPackets = 1, downBytes = 0, downPackets = 0) shouldBe Health.Stalled
    }

    @Test
    fun `a quiet session stays idle, never stalled`() {
        Feed().repeat(120, 0, 0, 0, 0) shouldBe Health.Idle
    }

    @Test
    fun `control-only chatter in both directions is idle`() {
        Feed().repeat(60, upBytes = 52, upPackets = 1, downBytes = 52, downPackets = 1) shouldBe Health.Idle
    }

    @Test
    fun `a working download is open`() {
        Feed().repeat(
            5,
            upBytes = 2_000,
            upPackets = 40,
            downBytes = 1_400_000,
            downPackets = 1_000
        ) shouldBe Health.Open
    }

    @Test
    fun `one data-bearing packet among many acks still counts as data`() {
        Feed().tick(upBytes = 800, upPackets = 2, downBytes = 20 * 52 + 1_400, downPackets = 21) shouldBe Health.Open
    }

    @Test
    fun `a small dns answer counts as data`() {
        Feed().tick(upBytes = 90, upPackets = 1, downBytes = 110, downPackets = 1) shouldBe Health.Open
    }

    @Test
    fun `ipv6 control packets are not mistaken for data`() {
        val f = Feed()
        f.repeat(21, upBytes = 900, upPackets = 3, downBytes = 3 * 72, downPackets = 3) shouldBe Health.Stalled
    }

    @Test
    fun `recovery from a stall is immediate`() {
        val f = Feed()
        f.repeat(21, deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Stalled
        f.tick(upBytes = 500, upPackets = 2, downBytes = 30_000, downPackets = 22) shouldBe Health.Open
    }

    @Test
    fun `a stall decays to idle once uplink has been quiet for the window`() {
        val f = Feed()
        f.repeat(21, deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Stalled
        f.repeat(19, 0, 0, 0, 0) shouldBe Health.Stalled
        f.repeat(2, 0, 0, 0, 0) shouldBe Health.Idle
    }

    @Test
    fun `open decays to idle once downlink data is older than the window`() {
        val f = Feed()
        f.tick(upBytes = 500, upPackets = 2, downBytes = 30_000, downPackets = 22) shouldBe Health.Open
        f.repeat(20, 0, 0, 0, 0) shouldBe Health.Open
        f.tick(0, 0, 0, 0) shouldBe Health.Idle
    }

    // Review Focus #2: a retained-TUN restart mid-stall gives the new core a fresh window.
    @Test
    fun `reset mid-stall starts the new epoch idle with a fresh clock`() {
        val f = Feed()
        f.repeat(21, deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Stalled
        f.detector.reset()
        f.tick(deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Idle
        f.repeat(18, deadTcpUp.first, deadTcpUp.second, deadTcpDown.first, deadTcpDown.second) shouldBe Health.Idle
    }

    // Review Focus #1: a stale or foreign sample with smaller totals rebaselines, never a delta.
    @Test
    fun `falling cumulative totals rebaseline instead of producing a negative delta`() {
        val d = HealthDetector()
        d.accept(TrafficSample(10_000, 10_000, 100, 100), 0L)
        d.accept(TrafficSample(10, 10, 1, 1), 1_000L) shouldBe Health.Idle
        d.accept(TrafficSample(10, 30_010, 1, 23), 2_000L) shouldBe Health.Open
    }
}
