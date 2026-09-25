// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.core.model.TagTraffic
import space.getsub.core.model.TrafficSample
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * F4's race hazard, reproduced deterministically the same way [LogcatReaderTest]'s F3 probe
 * is: real threads and [CountDownLatch]s, not a virtual-time test dispatcher, because the
 * defect is specifically about two genuinely concurrent coroutines — [TrafficSamplerLoop.start]'s
 * own polling job, and whatever coroutine `TunnelService.attachRetainedTun` calls
 * [TrafficSamplerLoop.notifyCountersRestarted] from — racing on wall-clock time.
 */
class TrafficSamplerLoopTest {
    private fun reading(
        up: Long,
        down: Long,
    ) = TunnelCounters(up, down, 0, 0)

    /**
     * The hazard F4 names explicitly: "the loop currently does read() then
     * sampler.accept(...); a signal landing between those two calls must not corrupt the
     * result." [TrafficSamplerLoop.start]'s own `read()` call has nothing serialising it
     * against a concurrent [TrafficSamplerLoop.notifyCountersRestarted] — this pins a `read()`
     * call in flight with a latch, fires the restart signal while it is parked, then releases
     * it, so the race is not a timing hope but the only order this test can produce.
     *
     * The values match the reviewer's own probe (F4: "before=100, new epoch raw=150,
     * observed=150, expected=250"): two unraced ticks establish 100 accumulated (the first
     * reading of a session baselines rather than counts — [TrafficSampler]'s own "the first
     * reading is the baseline, not a spike" — so it takes a second tick's delta to actually
     * accumulate anything). A third tick's `read()` is then parked mid-flight; while parked,
     * the test signals a restart and only then lets `read()` return a value **equal to the
     * pre-restart total** — the exact shape of reading a live-but-about-to-reset counter can
     * plausibly produce, and ambiguous under either epoch's rules (the old epoch's delta would
     * be 0; the new epoch's full count would be 100 — neither is right, because this reading
     * cannot be trusted to belong to either epoch). A clean fourth tick then supplies the new
     * epoch's real first reading, 150.
     *
     * The fix must discard the racing tick entirely (no emitted sample from it) and count the
     * clean 150 in full against the preserved 100, landing on the reviewer's own expected total,
     * 250 — not 100 (the preserved total alone, the tainted reading contributing nothing), not
     * 200 (the tainted reading counted as this epoch's own on top of it), and not 150 (the
     * preserved total silently lost, as it is again in this test's very first, no-fix run: the
     * two ordinary ticks omitted below collapsed [uplinkBytes]'s [TrafficSampler] delta-based
     * accumulation into a single first-ever baseline, and the discarded racing tick was mistaken
     * for the whole pre-restart total instead of a second one). Without
     * [TrafficSamplerLoop.notifyCountersRestarted] existing at all, this does not compile — an
     * automatic failure against the pre-fix code.
     */
    @Test
    fun `a restart signal landing mid-read does not corrupt the next accumulated sample`() {
        val thirdReadEntered = CountDownLatch(1)
        val releaseThirdRead = CountDownLatch(1)
        val readCount = AtomicInteger(0)
        val samples = mutableListOf<TrafficSample>()
        val gotThirdSample = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val loop =
            TrafficSamplerLoop(
                scope = scope,
                read = {
                    when (readCount.incrementAndGet()) {
                        // Unraced: the session's own baseline reading, per TrafficSampler's
                        // documented first-reading behaviour. Counts nothing yet.
                        1 -> reading(0, 0)
                        // Unraced: the first real accumulation -- delta(0, 100) = 100.
                        2 -> reading(100, 100)
                        // The tainted read: parked until the test fires the restart signal, then
                        // returns a reading equal to the pre-restart total -- ambiguous by
                        // construction, exactly what the epoch bracket must discard rather than
                        // feed to accept() under either epoch's rules.
                        3 -> {
                            thirdReadEntered.countDown()
                            releaseThirdRead.await(5, TimeUnit.SECONDS)
                            reading(100, 100)
                        }
                        // The new epoch's clean first reading -- the reviewer's own probe value.
                        else -> reading(150, 150)
                    }
                },
                emit = { sample ->
                    samples.add(sample)
                    if (samples.size == 3) gotThirdSample.countDown()
                },
                intervalMillis = 5,
            )

        try {
            loop.start()
            assertTrue("third read never started", thirdReadEntered.await(5, TimeUnit.SECONDS))
            loop.notifyCountersRestarted()
            releaseThirdRead.countDown()

            assertTrue("did not observe a third emitted sample", gotThirdSample.await(5, TimeUnit.SECONDS))
        } finally {
            loop.stop()
            scope.cancel()
        }

        // Sample 1 (tick 1): baseline, nothing accumulated yet.
        assertEquals(0L, samples[0].uplinkBytes)
        // Sample 2 (tick 2): the ordinary delta, establishing 100 accumulated before the restart.
        assertEquals(100L, samples[1].uplinkBytes)
        // Sample 3 is tick 4's result, not tick 3's: the tainted reading produced no emission at
        // all. 100 (preserved) + 150 (counted in full) = 250, the reviewer's own expected value.
        assertEquals(250L, samples[2].uplinkBytes)
        assertEquals(250L, samples[2].downlinkBytes)
    }

    /**
     * [TrafficSamplerLoop.start]'s own KDoc on `appliedRestartEpoch`: a restart signal that
     * arrives before this session's job has even launched — e.g. left over from a session that
     * already stopped — must not be replayed onto the brand-new [TrafficSampler] as though it
     * were this session's own restart. A brand-new sampler already baselines its first reading
     * correctly; treating a stale signal as current would instead count that first reading in
     * full, reproducing the exact "wrong first reading" bug the ordinary (no-restart) baseline
     * behaviour exists to prevent.
     */
    @Test
    fun `a restart signal from before start is not replayed onto a freshly started sampler`() {
        val samples = mutableListOf<TrafficSample>()
        val gotSample = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val loop =
            TrafficSamplerLoop(
                scope = scope,
                read = { reading(50, 50) },
                emit = { sample ->
                    samples.add(sample)
                    gotSample.countDown()
                },
                intervalMillis = 5,
            )

        try {
            // Simulates a signal left over from a previous session -- notifyCountersRestarted()
            // is documented safe to call before start(), or after the job it was meant for has
            // already stopped.
            loop.notifyCountersRestarted()
            loop.start()

            assertTrue("no sample observed", gotSample.await(5, TimeUnit.SECONDS))
        } finally {
            loop.stop()
            scope.cancel()
        }

        // Still baselined, not counted in full -- the stale signal must not have primed this
        // sampler's very first reading as though it were a mid-session restart.
        assertEquals(0L, samples[0].uplinkBytes)
    }

    // --- I-1 / ruling R42: TunnelService.restartCoreRetainingTun's *other* branch ---
    //
    // attachRetainedTun's own call to notifyCountersRestarted() was already covered above.
    // restartCoreRetainingTun has a second branch -- taken when the retained TUN would stop
    // advertising the DNS the rebuilt core expects -- that goes through attachTun instead. That
    // branch also stops and restarts tun2socks and xray, but never calls TrafficSamplerLoop.stop(),
    // so start() is a no-op there (the job is still active) and the *same* TrafficSampler survives
    // into the new epoch. The tests below model exactly that shape: the loop is already running
    // (start() already called once, never stopped) when notifyCountersRestarted() fires, mid-
    // session, the same way attachTun's rebuild call now does. Without that call, TrafficSampler
    // would fall back to delta()'s inference on the next reading -- undercounting a rising reading
    // (below) and losing the interval entirely on an equal one (further below), for totals and for
    // per-tag rows alike.

    @Test
    fun `the rebuild path counts a rising new-epoch reading in full, for totals and per-tag`() {
        val tick = AtomicInteger(0)
        val gotSecondSample = CountDownLatch(1)
        val restartSignalled = CountDownLatch(1)
        val samples = mutableListOf<TrafficSample>()
        val gotThirdSample = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val loop =
            TrafficSamplerLoop(
                scope = scope,
                read = {
                    when (tick.incrementAndGet()) {
                        1 -> reading(0, 0) // baseline: nothing accumulated yet
                        2 -> reading(100, 100) // ordinary delta: accumulated = 100
                        else -> {
                            // Mirrors attachTun's rebuild branch: the signal is fired once, from
                            // outside this loop's coroutine, strictly before this tick's read --
                            // not raced mid-read (TrafficSamplerLoopTest's first test already
                            // covers that harder case).
                            restartSignalled.await(5, TimeUnit.SECONDS)
                            reading(150, 150) // new epoch's first reading -- above the old prev
                        }
                    }
                },
                readTags = {
                    when (tick.get()) {
                        1 -> listOf(TagTraffic("proxy", 0, 0))
                        2 -> listOf(TagTraffic("proxy", 100, 100))
                        else -> listOf(TagTraffic("proxy", 150, 150))
                    }
                },
                emit = { sample ->
                    samples.add(sample)
                    if (samples.size == 2) gotSecondSample.countDown()
                    if (samples.size == 3) gotThirdSample.countDown()
                },
                intervalMillis = 5,
            )

        try {
            loop.start()
            assertTrue("second sample never arrived", gotSecondSample.await(5, TimeUnit.SECONDS))
            // The rebuild branch's call site: fired here, with the loop still running -- start()
            // is never called again, exactly like attachTun's rebuild branch never calling it a
            // second time because trafficLoop.stop() was never reached on that path.
            loop.notifyCountersRestarted()
            restartSignalled.countDown()

            assertTrue("third sample never arrived", gotThirdSample.await(5, TimeUnit.SECONDS))
        } finally {
            loop.stop()
            scope.cancel()
        }

        // 100 (preserved) + 150 (counted in full) = 250, not 150 (delta(100, 150) = 50, landing on
        // 150 total) -- the exact undercount ruling R38 was filed to remove, reproduced here on the
        // branch F4's original fix did not reach.
        assertEquals(250L, samples[2].uplinkBytes)
        assertEquals(250L, samples[2].downlinkBytes)
        val proxy = samples[2].perTag.single { it.tag == "proxy" }
        assertEquals(250L, proxy.uplinkBytes)
        assertEquals(250L, proxy.downlinkBytes)
    }

    @Test
    fun `the rebuild path counts a new-epoch reading equal to the old prev in full, for totals and per-tag`() {
        val tick = AtomicInteger(0)
        val gotSecondSample = CountDownLatch(1)
        val restartSignalled = CountDownLatch(1)
        val samples = mutableListOf<TrafficSample>()
        val gotThirdSample = CountDownLatch(1)

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val loop =
            TrafficSamplerLoop(
                scope = scope,
                read = {
                    when (tick.incrementAndGet()) {
                        1 -> reading(0, 0)
                        2 -> reading(100, 100) // accumulated = 100
                        else -> {
                            restartSignalled.await(5, TimeUnit.SECONDS)
                            // The equality case review finding I-1 calls out as "worse":
                            // delta(100, 100) = 0 would silently discard this whole interval.
                            reading(100, 100)
                        }
                    }
                },
                readTags = {
                    when (tick.get()) {
                        1 -> listOf(TagTraffic("proxy", 0, 0))
                        2 -> listOf(TagTraffic("proxy", 100, 100))
                        else -> listOf(TagTraffic("proxy", 100, 100))
                    }
                },
                emit = { sample ->
                    samples.add(sample)
                    if (samples.size == 2) gotSecondSample.countDown()
                    if (samples.size == 3) gotThirdSample.countDown()
                },
                intervalMillis = 5,
            )

        try {
            loop.start()
            assertTrue("second sample never arrived", gotSecondSample.await(5, TimeUnit.SECONDS))
            loop.notifyCountersRestarted()
            restartSignalled.countDown()

            assertTrue("third sample never arrived", gotThirdSample.await(5, TimeUnit.SECONDS))
        } finally {
            loop.stop()
            scope.cancel()
        }

        // 100 (preserved) + 100 (counted in full) = 200, not 100 (delta(100, 100) = 0 -- the
        // interval lost entirely).
        assertEquals(200L, samples[2].uplinkBytes)
        assertEquals(200L, samples[2].downlinkBytes)
        val proxy = samples[2].perTag.single { it.tag == "proxy" }
        assertEquals(200L, proxy.uplinkBytes)
        assertEquals(200L, proxy.downlinkBytes)
    }

    @Test
    fun `the initial-connect call does not spike the first reading, for totals or per-tag`() {
        // Mirrors attachTun's initial-connect call: notifyCountersRestarted() fires before
        // start() has ever been called for this session, because the call site does not
        // distinguish the initial-connect case from the rebuild case -- it relies on start()'s
        // own seeding (see its KDoc) to make the initial-connect case harmless.
        val tick = AtomicInteger(0)
        val samples = mutableListOf<TrafficSample>()
        val gotSecondSample = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val loop =
            TrafficSamplerLoop(
                scope = scope,
                read = {
                    when (tick.incrementAndGet()) {
                        1 -> reading(50, 50)
                        else -> reading(80, 80)
                    }
                },
                readTags = {
                    when (tick.get()) {
                        1 -> listOf(TagTraffic("proxy", 20, 20))
                        else -> listOf(TagTraffic("proxy", 35, 35))
                    }
                },
                emit = { sample ->
                    samples.add(sample)
                    if (samples.size == 2) gotSecondSample.countDown()
                },
                intervalMillis = 5,
            )

        try {
            loop.notifyCountersRestarted()
            loop.start()

            assertTrue("second sample never arrived", gotSecondSample.await(5, TimeUnit.SECONDS))
        } finally {
            loop.stop()
            scope.cancel()
        }

        // Tick 1: the session totals still baseline (0, not 50) -- a signal fired ahead of this
        // session's first start() must not be replayed onto that first reading as a restart.
        assertEquals(0L, samples[0].uplinkBytes)
        assertEquals(0L, samples[0].downlinkBytes)
        // Tick 2: an ordinary delta against the baseline, not a second full count --
        // delta(50, 80) = 30, confirming the epoch tracker settled rather than staying primed.
        assertEquals(30L, samples[1].uplinkBytes)
        assertEquals(30L, samples[1].downlinkBytes)
        // Per-tag: "proxy"'s own first-sighting rule already counts its first reading in full
        // regardless of any restart signal (TrafficSampler's documented, unrelated behaviour) --
        // what this proves is tick 2 is an ordinary delta (35 - 20 = 15 more), not a second
        // full count of 35 stacked on top.
        val proxyTick1 = samples[0].perTag.single { it.tag == "proxy" }
        assertEquals(20L, proxyTick1.uplinkBytes)
        val proxyTick2 = samples[1].perTag.single { it.tag == "proxy" }
        assertEquals(35L, proxyTick2.uplinkBytes)
        assertEquals(35L, proxyTick2.downlinkBytes)
    }
}
