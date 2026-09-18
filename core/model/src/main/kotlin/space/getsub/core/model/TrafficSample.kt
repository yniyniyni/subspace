// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * Accumulated traffic for one tunnel session.
 *
 * Bytes counted at the TUN interface, so this is everything the tunnel carries
 * — including traffic a routing rule sends `direct`. There is deliberately no
 * proxied-vs-direct split here; that is the opt-in per-tag breakdown (M8.5
 * spec §2), and conflating the two would let a UI imply a breakdown it does not
 * have.
 *
 * Per session by construction: hev zeroes its counters in the teardown path, so
 * there is no lifetime total to read. A session survives a core restart that
 * retains the TUN, which is what a user expects across a Wi-Fi ↔ cellular
 * change (spec §1.4).
 */
public data class TrafficSample(
    val uplinkBytes: Long,
    val downlinkBytes: Long,
    val uplinkPackets: Long,
    val downlinkPackets: Long,
) {
    public companion object {
        public val ZERO: TrafficSample = TrafficSample(0, 0, 0, 0)
    }
}
