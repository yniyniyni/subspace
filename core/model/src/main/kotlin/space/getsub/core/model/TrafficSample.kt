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
    /**
     * Per-outbound-tag rows, empty unless the user enabled the breakdown
     * (M8.5 spec §2) — and empty for a `RAW_JSON` profile running in the pure
     * passthrough branch, which cannot carry a stats block at all (spec §2.4).
     *
     * Empty therefore does not mean "no traffic". The UI must distinguish
     * "breakdown off", "not available for this profile" and "on, but nothing
     * has moved yet" — rendering an empty list as zeros would be
     * `ARCHITECTURE.md` §10.1's signature failure.
     */
    val perTag: List<TagTraffic> = emptyList(),
)

/**
 * Bytes moved by one outbound tag this session (M8.5 spec §2).
 *
 * Public and in `:core:model` — not `:service`, where it originated — so it
 * can cross the AIDL boundary and reach `:feature:home`, where the breakdown
 * renders (§4: `:service` may not depend on `:feature:*`, so the type has to
 * live upstream of both).
 *
 * [tag] is an outbound tag from the user's own config and is config content
 * (`ARCHITECTURE.md` §5.6) — it must never be logged, only displayed back to
 * the user who wrote it.
 */
public data class TagTraffic(
    val tag: String,
    val uplinkBytes: Long,
    val downlinkBytes: Long,
)
