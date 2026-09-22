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
}
