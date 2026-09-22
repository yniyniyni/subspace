// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import space.getsub.core.model.TagTraffic
import space.getsub.core.model.TrafficSample
import java.util.concurrent.atomic.AtomicInteger

/**
 * The timer half of the sampler (spec §1.5).
 *
 * The sampler lives in `:bg` and runs whether or not a UI is bound, because
 * liveness (spec §4.3, Part 3) must detect an unreachable server with the app
 * backgrounded — `ConnectionState` is the service's property under
 * ARCHITECTURE.md §5.5. The UI is a second consumer of samples that already
 * exist, not the reason they are taken.
 *
 * **One `TrafficSampler` per session, not one for this loop's whole
 * lifetime.** [stop] only calls `Job.cancel()`, which is cooperative:
 * cancellation is observed at the next [delay], not mid-body, so a call
 * landing between the `while (isActive)` check and `delay` lets that
 * iteration's [TrafficSampler.accept] run to completion regardless.
 * [TerminalOutcome]'s own KDoc documents this exact class of bug
 * ("`cancel()` alone cannot stop it"). A fast disconnect-then-reconnect can
 * therefore have the old job's tail end still inside `accept()` while
 * [start] launches a new job — and `TrafficSampler` is documented
 * not-thread-safe, plain unsynchronised `var`s. Sharing one instance across
 * both jobs would let the dying job's `accept()` corrupt the new session's
 * accumulation. [start] instead builds a fresh [TrafficSampler] and captures
 * it in the launched coroutine's closure, so an old job's tail end and a new
 * job's start operate on two separate instances with nothing shared to
 * corrupt.
 *
 * This narrows the failure to one **residual**, deliberately left open: the
 * old job can still call [emit] once, with a stale sample from the session
 * that just ended, after [start] has already begun the new one. That is a
 * transient display value — the next tick overwrites it — not corrupted
 * state, and it is what the `isActive` check inside the loop below narrows
 * (not closes: any check-then-act here is still a race) rather than a
 * guarantee this class makes.
 *
 * @param emit called with each accumulated sample. **It must broadcast under
 *   `TunnelService`'s own lock**: `RemoteCallbackList.beginBroadcast()` throws
 *   when a broadcast is already in flight, so an unsynchronised per-second push
 *   would race state publication and take the service down.
 * @param readTags reads the current per-tag breakdown, or null while the
 *   breakdown is off (M8.5 spec §2). Called once per tick, only when [read]
 *   produced a reading — a session with nothing to sample has nothing to
 *   attribute either. Absent entirely by default so every existing caller
 *   keeps compiling; [TunnelService] supplies it from the loopback metrics
 *   listener when one is running.
 *
 *   The raw reading is handed to [TrafficSampler.accept] alongside [read]'s
 *   reading, in the same call (review finding I1) — this loop no longer
 *   `copy()`s a raw tag reading onto the emitted sample after the fact. xray
 *   is restarted by a retained-TUN restart exactly like tun2socks is, so a raw
 *   per-tag reading resets to zero on every Wi-Fi<->cellular handoff while the
 *   session total keeps accumulating; routing both through the sampler's own
 *   delta logic keeps them on one clock. [TrafficSampler] is the only thing
 *   that may decide what the emitted `perTag` is — this loop cannot see a raw
 *   reading the sampler has not folded in.
 */
internal class TrafficSamplerLoop(
    private val scope: CoroutineScope,
    private val read: () -> TunnelCounters?,
    private val emit: (TrafficSample) -> Unit,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val readTags: (() -> List<TagTraffic>)? = null,
) {
    private var job: Job? = null

    /** Guards [job]. See [start]'s KDoc for why this exists. */
    private val lock = Any()

    /**
     * A monotonic epoch marker, bumped once per [notifyCountersRestarted] call. [start]'s loop
     * body reads this both immediately before and immediately after [read], so a restart landing
     * anywhere inside that call — including a genuine data race on the native counters themselves
     * ([Tun2Socks.stats]'s own KDoc: hev's counters are "plain non-atomic size_t globals") — is
     * detected and that tick's reading is discarded rather than fed to [TrafficSampler.accept].
     * [AtomicInteger] rather than a plain `var`: [notifyCountersRestarted] is called from
     * `TunnelService`'s own coroutine, a different one from [start]'s `scope.launch` body, so this
     * field is genuinely written and read from two threads with nothing else serializing them.
     *
     * Deliberately never reset to 0 on [stop]/[start]: see [start]'s own comment on why a fresh
     * job seeds its local `appliedRestartEpoch` from this field's *current* value instead.
     */
    private val restartSignal = AtomicInteger(0)

    /**
     * **Synchronized with [stop] — do not remove this lock.** [start] is
     * reached from `attachTun`/`attachRetainedTun`; [stop] is reached from
     * `TunnelService.stopTunnel`, which `onRevoke()` and `onDestroy()` call
     * **directly**, bypassing the command coordinator — the same fact
     * `tun2socks_jni.c`'s locking-contract comment cites: "§5.4 says
     * disconnect, onRevoke, and onDestroy are not serialised with each
     * other". Unsynchronized, a `stop()` reading [job] into a local and a
     * concurrent `start()` racing `job?.isActive`/`job = …` can leave the new
     * job assigned *after* the old one is cancelled — the same orphaning
     * shape as [space.getsub.service.log.LogCapture.start]/`stop`, just with
     * a coroutine instead of a thread and `stopTunnel`'s trailing sampler
     * left running instead of `logcat`. `synchronized` around these two short
     * bodies closes that window without blocking: `Job.cancel()` is
     * fire-and-forget (this class's own KDoc above explains why it is
     * intentionally not `cancelAndJoin` — ruling R18), so nothing inside
     * either critical section can wait.
     */
    fun start() {
        synchronized(lock) {
            if (job?.isActive == true) return
            val sampler = TrafficSampler()
            // Seeded from restartSignal's *current* value, not 0: a notifyCountersRestarted()
            // call that landed after the previous session's stop() (or before this one's first
            // tick) must not be replayed onto a brand-new TrafficSampler, whose own first-ever
            // accept() already baselines correctly on its own. Treating a stale signal as "new"
            // here would call beginNewEpoch() before that first reading, turning a
            // baseline-and-discard into a count-in-full — exactly the wrong-first-reading bug
            // TrafficSampler's own "the first reading is the baseline, not a spike" test guards
            // against.
            var appliedRestartEpoch = restartSignal.get()
            job =
                scope.launch {
                    while (isActive) {
                        // Brackets the one call that can race notifyCountersRestarted() on
                        // another thread. See restartSignal's own KDoc for why this is an
                        // AtomicInteger rather than a plain var.
                        val epochBeforeRead = restartSignal.get()
                        val reading = read()
                        val epochAfterRead = restartSignal.get()

                        if (epochAfterRead != appliedRestartEpoch) {
                            // A restart was signalled at some point up to and including this
                            // read — apply it to the sampler now, from this coroutine, so
                            // TrafficSampler's own not-thread-safe contract is never crossed.
                            sampler.beginNewEpoch()
                            appliedRestartEpoch = epochAfterRead
                        }

                        if (epochBeforeRead == epochAfterRead) {
                            reading?.let {
                                val sample = sampler.accept(it, readTags?.invoke() ?: emptyList())
                                // Narrows, does not close, the residual this class's
                                // KDoc names: a cancellation landing after accept()
                                // but before this check still slips one stale emit
                                // through. Deliberately not stronger than that.
                                if (isActive) emit(sample)
                            }
                        }
                        // epochBeforeRead != epochAfterRead: the restart landed while read() was
                        // in flight, so this reading may be a stale tail of the old epoch, a
                        // partial view of the new one, or a torn read of the native counters
                        // themselves — any of which would corrupt accept() either way. It is
                        // discarded outright rather than fed to the sampler; beginNewEpoch() above
                        // already primed the sampler so the *next* tick's reading — no longer
                        // racing anything — is counted in full instead.

                        delay(intervalMillis)
                    }
                }
        }
    }

    /** See [start]'s KDoc — synchronized with it for the same reason. */
    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }

    /**
     * Tells this loop the counters [read] and [readTags] poll were just reset by an explicit
     * restart — `TunnelService.restartCoreRetainingTun` stopping and restarting hev and xray
     * between polls — instead of leaving [TrafficSampler] to infer it from a falling reading
     * (ruling R38, amending R28; [TrafficSampler]'s own KDoc has the full reasoning).
     *
     * Safe to call from any thread: this only bumps [restartSignal], an [AtomicInteger]. The
     * actual [TrafficSampler.beginNewEpoch] call happens later, inside [start]'s own coroutine,
     * which is what keeps [TrafficSampler] — documented not-thread-safe — touched from exactly
     * one place. Call this as early as the caller can be sure the *new* epoch's counters are the
     * ones a subsequent [read] will see: too early (before the old core has actually stopped) can
     * let a still-climbing old-epoch reading be double-counted as the new epoch's first; too late
     * re-opens the undercount window this exists to close. `TunnelService.attachRetainedTun`
     * calls this immediately after `Tun2Socks.start()` confirms the new tunnel — and therefore the
     * new counters — are live, before any further suspending work (foreground promotion, the
     * connection record's persistence) that could let [start]'s loop poll a genuine new-epoch
     * value the sampler has not yet been told to expect.
     *
     * A call while [start]'s job is not running (nothing connected) is harmless: [start] seeds its
     * local epoch tracker from [restartSignal]'s value when it launches, so a signal from a
     * session that already ended is never replayed onto the next one.
     */
    fun notifyCountersRestarted() {
        restartSignal.incrementAndGet()
    }

    internal companion object {
        /**
         * Spec §1.5: a guess at where display smoothness and wakeup cost
         * balance, named rather than inlined so spec §9 row 5 has one place to
         * change. Do not tune it by reasoning.
         */
        const val DEFAULT_INTERVAL_MILLIS: Long = 1_000
    }
}
