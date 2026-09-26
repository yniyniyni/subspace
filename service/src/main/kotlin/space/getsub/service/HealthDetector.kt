// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.Health
import space.getsub.core.model.TrafficSample

/**
 * Decides [Health] from the TUN-level counters (M8.5 spec §4.3, as amended).
 *
 * **Why not "downlink flat".** hev completes TCP with the app locally, in lwIP,
 * before xray dials anything (`tcp_accept_handler`, hev `src/hev-socks5-tunnel.c:163`),
 * and a failed session aborts with a RST (`src/hev-socks5-session-tcp.c:305`). So
 * a dead server still produces downlink *bytes*: SYN-ACKs, ACKs, RSTs. What it
 * never produces is downlink *data*. Each second is therefore classed per
 * direction as carrying data or control only, from bytes against packets.
 *
 * **Asymmetry (M8.5 spec §4.2).** Only "we sent data and nothing came back" is a
 * stall. A session sending nothing is [Health.Idle], because a working tunnel
 * and a dead one are indistinguishable when nothing is asked of them.
 *
 * **Limits:** a dead proxy reads [Health.Open] while direct-routed traffic
 * flows, since the counters cannot split the two (ARCHITECTURE.md §14.4); an
 * HTTP/1.1 upload receiving no bytes for longer than the window reads
 * [Health.Stalled].
 *
 * Pure: no clock, no timer, no Android. Not thread-safe; driven from the
 * sampler's emit path under `TunnelService`'s lock.
 */
internal class HealthDetector(
    private val stallWindowMillis: Long = STALL_WINDOW_MILLIS,
    private val controlPacketCeilingBytes: Long = CONTROL_PACKET_CEILING_BYTES,
) {
    private var previous: TrafficSample? = null
    private var lastDownlinkDataAt: Long? = null
    private var lastUplinkDataAt: Long? = null
    private var firstUnansweredUplinkAt: Long? = null
    private var health = Health.Idle

    fun accept(
        sample: TrafficSample,
        nowMillis: Long,
    ): Health {
        val prev = previous
        previous = sample
        if (prev != null) {
            val upBytes = sample.uplinkBytes - prev.uplinkBytes
            val upPackets = sample.uplinkPackets - prev.uplinkPackets
            val downBytes = sample.downlinkBytes - prev.downlinkBytes
            val downPackets = sample.downlinkPackets - prev.downlinkPackets
            // A falling total is not traffic: a stale emit from a finished sampler, or a
            // sample from a different accumulator. Rebaseline and conclude nothing.
            if (isValidDelta(upBytes, upPackets, downBytes, downPackets)) {
                updateHealth(upBytes, upPackets, downBytes, downPackets, nowMillis)
            }
        }
        return health
    }

    private fun updateHealth(
        upBytes: Long,
        upPackets: Long,
        downBytes: Long,
        downPackets: Long,
        nowMillis: Long,
    ) {
        val downData = carriesData(downBytes, downPackets)
        val upData = carriesData(upBytes, upPackets)
        if (downData) {
            lastDownlinkDataAt = nowMillis
            firstUnansweredUplinkAt = null
        }
        if (upData) {
            lastUplinkDataAt = nowMillis
            if (!downData && firstUnansweredUplinkAt == null) firstUnansweredUplinkAt = nowMillis
        }
        // Uplink quiet for the whole window: nobody is waiting on an answer any more.
        if (lastUplinkDataAt?.let { nowMillis - it >= stallWindowMillis } == true) {
            firstUnansweredUplinkAt = null
        }

        val isStalled = firstUnansweredUplinkAt?.let { nowMillis - it >= stallWindowMillis } == true
        val isOpen = lastDownlinkDataAt?.let { nowMillis - it <= stallWindowMillis } == true
        health = when {
            isStalled -> Health.Stalled
            isOpen -> Health.Open
            else -> Health.Idle
        }
    }

    /** A new counter epoch (retained-TUN restart): the new core gets a fresh window. */
    fun reset() {
        previous = null
        lastDownlinkDataAt = null
        lastUplinkDataAt = null
        firstUnansweredUplinkAt = null
        health = Health.Idle
    }

    private fun isValidDelta(
        upBytes: Long,
        upPackets: Long,
        downBytes: Long,
        downPackets: Long,
    ): Boolean = upBytes >= 0 && upPackets >= 0 && downBytes >= 0 && downPackets >= 0

    private fun carriesData(
        bytes: Long,
        packets: Long,
    ): Boolean = packets > 0 && bytes > packets * controlPacketCeilingBytes

    internal companion object {
        /** M8.5 spec §4.3's guess, tuned only by §9 row 6. */
        const val STALL_WINDOW_MILLIS: Long = 20_000L

        /** An IPv6 TCP control packet (60 B) plus options. Tuned only by §9 row 6. */
        const val CONTROL_PACKET_CEILING_BYTES: Long = 80L
    }
}
