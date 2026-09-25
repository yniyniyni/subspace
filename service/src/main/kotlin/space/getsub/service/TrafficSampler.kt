// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.TagTraffic
import space.getsub.core.model.TrafficSample

/**
 * Turns hev's raw cumulative counters into a session total.
 *
 * Pure by construction: it owns no timer, opens no JNI call and touches no
 * clock, so every edge below is a JVM unit test rather than a device
 * observation. [TrafficSamplerLoop] supplies the timer.
 *
 * **Why deltas rather than the raw value** (spec §1.3): hev's counters are
 * plain `static size_t`, and we build `arm64-v8a`, `armeabi-v7a` and
 * `x86_64` (`service/build.gradle.kts`). On the 32-bit ABI, `size_t` is 32
 * bits, so a byte counter can wrap at 4 GiB and the raw value would fall back
 * to near zero mid-session; on the two 64-bit ABIs it cannot wrap at all, but
 * a retained-TUN restart on a network handoff resets the counter to whatever
 * it held before, and that reset also makes the reading fall. A falling
 * reading is therefore ambiguous — a 32-bit wrap and a genuine reset look
 * identical once the previous value is below 2³² — and there is no way to
 * tell them apart from the two numbers alone, so [delta] does not try. It
 * treats every falling reading as the start of a fresh counter epoch and
 * returns the new value as-is. Getting this wrong the other way was a real
 * defect: on a 64-bit ABI a retained-TUN restart always produces a falling
 * reading with the previous value nowhere near 2³², so any inference that
 * called that a wrap added a spurious 4 GiB per network transition.
 *
 * The first reading is a baseline, not traffic: a sampler attached to a session
 * already in flight must not report the counter's whole history as this
 * session's.
 *
 * **Per-tag rows are accumulated here too (review finding I1), on the same
 * clock as the totals above.** [TrafficSamplerLoop] used to `copy()` a raw,
 * un-accumulated reading from xray's `/debug/vars` onto the emitted sample,
 * which bypassed this class' delta logic entirely. xray is restarted by a
 * retained-TUN restart exactly like tun2socks is, so its per-tag counters
 * reset to zero on every Wi-Fi<->cellular handoff — while the totals above
 * kept accumulating. From the first handoff onward the per-tag rows and the
 * total they claim to decompose disagreed permanently. [accept] now takes the
 * tick's tag readings and folds them through [TagAccumulator], which applies
 * the same falling-reading rule [delta] does, so both are on one clock.
 *
 * **A tag's first reading counts in full, unlike the totals' first reading.**
 * They differ for a real reason: this sampler can attach to a tunnel already
 * in flight, which is why the totals baseline their first reading rather than
 * reporting the counter's whole history as this session's. A tag cannot have
 * that problem — xray's lifetime is bounded by the session
 * ([space.getsub.service.TunnelService.startCore] starts it, teardown stops
 * it — see `TunnelService`'s KDoc) — so the first reading [TagAccumulator]
 * ever sees for a tag *is* the whole of this session's traffic for that tag so
 * far. Baselining it would silently discard everything the tag moved before
 * the metrics endpoint answered its first poll.
 *
 * **A tag that disappears from a later reading keeps its accumulated value.**
 * [tagAccumulators] is only ever added to or updated for tags present in the
 * current tick; a tag missing this tick is simply left alone, not removed —
 * xray restarting with a different config must not erase traffic already
 * counted for this session under the old one.
 *
 * **Restart epochs are now signalled explicitly, not inferred (ruling R38,
 * amending R28).** [delta]'s own KDoc used to be the only place this class
 * decided a falling reading meant a fresh epoch — correct at the instant a
 * retained-TUN restart resets the counter, but the sampler does not read at
 * that instant: it reads up to one interval later ([TrafficSamplerLoop]
 * samples at 1 s), and by then the new epoch can already have passed the old
 * reading. `delta(prev=100, curr=150)` after a restart returns `50`, not the
 * new epoch's true `150`; the equality case is worse, losing the new epoch's
 * traffic entirely. [beginNewEpoch] is [TrafficSamplerLoop]'s way of telling
 * this class the counters were just reset by
 * [space.getsub.service.TunnelService.restartCoreRetainingTun], which removes
 * the guess for the one case that actually happens on this hardware. [delta]
 * stays exactly as it was for the case that has no signal — a genuine 32-bit
 * wrap on `armeabi-v7a` — so R28 is narrowed, not reversed: inference still
 * beats the old wrap-arithmetic branch, it just is not trusted to cover a
 * restart anymore.
 *
 * **Not thread-safe.** Must be driven from a single coroutine or dispatcher;
 * concurrent calls to [accept] are not synchronized. [beginNewEpoch] carries
 * the same requirement — see its own KDoc for how [TrafficSamplerLoop] keeps
 * it off an in-flight [accept].
 */
internal class TrafficSampler {
    private var previous: TunnelCounters? = null
    private var uplinkBytes = 0L
    private var downlinkBytes = 0L
    private var uplinkPackets = 0L
    private var downlinkPackets = 0L

    /**
     * Per-tag accumulators, keyed by outbound tag. A [LinkedHashMap] so
     * iteration order is first-sighting order rather than whatever order the
     * current tick's JSON object happened to parse in — xray's own map
     * iteration order carries no guarantee, and the UI must not reshuffle rows
     * between ticks.
     */
    private val tagAccumulators = LinkedHashMap<String, TagAccumulator>()

    fun accept(
        reading: TunnelCounters,
        tags: List<TagTraffic> = emptyList(),
    ): TrafficSample {
        val prev = previous
        if (prev != null) {
            uplinkBytes += delta(prev.uplinkBytes, reading.uplinkBytes)
            downlinkBytes += delta(prev.downlinkBytes, reading.downlinkBytes)
            uplinkPackets += delta(prev.uplinkPackets, reading.uplinkPackets)
            downlinkPackets += delta(prev.downlinkPackets, reading.downlinkPackets)
        }
        previous = reading

        for (tag in tags) {
            tagAccumulators.getOrPut(tag.tag) { TagAccumulator() }.accept(tag.uplinkBytes, tag.downlinkBytes, ::delta)
        }

        return TrafficSample(
            uplinkBytes = uplinkBytes,
            downlinkBytes = downlinkBytes,
            uplinkPackets = uplinkPackets,
            downlinkPackets = downlinkPackets,
            perTag = tagAccumulators.map { (tag, acc) -> TagTraffic(tag, acc.uplinkBytes, acc.downlinkBytes) },
        )
    }

    /**
     * Tells this sampler the underlying counters were just reset by an
     * explicit restart — [space.getsub.service.TunnelService.restartCoreRetainingTun]
     * stopping and restarting hev and xray between polls — rather than
     * leaving [delta] to guess it from a falling reading (ruling R38,
     * amending R28; see this class' own KDoc).
     *
     * Resets the "previous reading" baseline to zero for the totals and
     * every tag [tagAccumulators] already knows about, **without touching
     * [uplinkBytes]/[downlinkBytes]/[uplinkPackets]/[downlinkPackets] or any
     * tag's accumulated total** — those are this session's traffic and
     * survive the restart exactly like they do today. The next reading of
     * each counter is a delta from zero, i.e. counted in full: the same
     * treatment [TagAccumulator]'s first-ever sighting already gets, for the
     * same reason — there is nothing to subtract because nothing before this
     * point belongs to the new epoch.
     *
     * This is deliberately not the whole-session `reset()` — there is no
     * such function anymore (deleted as dead code in `7b1fd2b`), and if
     * there were, calling it here would be wrong: it would discard the
     * accumulated totals this function exists to preserve.
     *
     * **Caller's responsibility, not this function's:** [TrafficSamplerLoop]
     * must not let this land concurrently with, or between the two halves
     * of, an in-flight [accept] — this class is not thread-safe and an
     * unsynchronized epoch reset racing a read is exactly the corruption
     * ruling R38 exists to close. See [TrafficSamplerLoop.notifyCountersRestarted]'s
     * KDoc for the mechanism.
     */
    fun beginNewEpoch() {
        previous = ZERO_COUNTERS
        tagAccumulators.values.forEach { it.beginNewEpoch() }
    }

    /**
     * Computes the difference between consecutive counter readings.
     *
     * A rising reading is the ordinary case: the delta is `curr - prev`.
     *
     * A falling reading (`curr < prev`) is ambiguous — it is either a 32-bit
     * wrap (`armeabi-v7a`) or a genuine reset from a retained-TUN restart
     * (any ABI, most commonly on a network handoff), and the two are
     * indistinguishable from `prev` and `curr` alone once `prev < 2³²`. This
     * always treats it as a reset and returns `curr` as-is:
     *
     * - If it really was a reset, that is exactly right.
     * - If it really was a wrap, this under-counts by `2³² - prev` — the
     *   bytes moved in the one sampling interval before the rollover
     *   ([TrafficSamplerLoop] samples at 1 s), so the loss is bounded by one
     *   second of traffic per 4 GiB crossed.
     *
     * The alternative — guessing wrap whenever `prev < 2³²` — is wrong every
     * time a retained-TUN restart fires on a 64-bit ABI, where `size_t` is 64
     * bits and the counter cannot wrap at all: it added a spurious ~4 GiB per
     * network transition, which is worse than this method's bounded
     * under-count on the ABI where a wrap can actually happen.
     */
    private fun delta(
        prev: Long,
        curr: Long,
    ): Long = if (curr >= prev) curr - prev else curr

    private companion object {
        /** [beginNewEpoch]'s baseline: a reading of zero from every counter, not "no reading yet". */
        val ZERO_COUNTERS =
            TunnelCounters(
                uplinkBytes = 0L,
                downlinkBytes = 0L,
                uplinkPackets = 0L,
                downlinkPackets = 0L,
            )
    }
}

/**
 * One outbound tag's accumulated traffic (review finding I1).
 *
 * Not thread-safe, for the same reason [TrafficSampler] is not: driven from
 * the single coroutine [TrafficSamplerLoop] owns.
 */
private class TagAccumulator {
    private var previousUplink: Long? = null
    private var previousDownlink: Long? = null

    var uplinkBytes: Long = 0L
        private set
    var downlinkBytes: Long = 0L
        private set

    /**
     * @param delta [TrafficSampler]'s own falling-reading rule, reused rather
     *   than duplicated: a tag's counter falling means xray restarted, the
     *   same fresh-epoch case the session totals already handle. No wrap
     *   inference here either — a previous version of that inference injected
     *   ~4 GiB per handoff and was deleted for it.
     */
    fun accept(
        uplink: Long,
        downlink: Long,
        delta: (prev: Long, curr: Long) -> Long,
    ) {
        uplinkBytes += stepDelta(previousUplink, uplink, delta)
        previousUplink = uplink
        downlinkBytes += stepDelta(previousDownlink, downlink, delta)
        previousDownlink = downlink
    }

    /**
     * [TrafficSampler.beginNewEpoch]'s per-tag half. Drops the "previous
     * reading" baseline — [uplinkBytes]/[downlinkBytes] (the accumulated
     * total) are untouched — so this tag's next reading goes through
     * [stepDelta]'s null-[prev] branch exactly as it would on this tag's
     * first-ever sighting: counted in full, nothing subtracted.
     */
    fun beginNewEpoch() {
        previousUplink = null
        previousDownlink = null
    }

    /**
     * A null [prev] means this is the tag's first sighting. Counted in full,
     * not baselined — see [TrafficSampler]'s own KDoc for why a tag's first
     * reading differs from the totals' first reading.
     */
    private fun stepDelta(
        prev: Long?,
        curr: Long,
        delta: (prev: Long, curr: Long) -> Long,
    ): Long = if (prev == null) curr else delta(prev, curr)
}
