// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.service.log.LineSink
import space.getsub.service.log.LogCapture
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * N1 / ruling R46: [LogCapture.stop] drains to a per-capture sentinel instead
 * of killing `logcat` on the spot.
 *
 * On a Pixel 8 the teardown's final `teardown[lifecycle] done` line reached
 * the ring in 0 of 14 stops, and sessions shorter than ~1.5 s captured
 * nothing. Three mechanisms, all on this side of the pipe: (a) a freshly
 * spawned `logcat` delivers nothing for ~1.9–3.9 s, and teardown destroyed it
 * before it had; (b) `done` is logged microseconds before the stop, before
 * even a warm `logcat` has written it; (c) lines already in the pipe were
 * discarded when the teardown thread closed the reader under a capture thread
 * that was behind.
 *
 * Every test here drives the real [LogCapture] and [LogcatReader] through
 * their seams: a [FakeLogd] decides when a logged line is delivered, the
 * deadline is a captured callback the test fires by hand, and every wait is a
 * bounded latch or queue poll — nothing sleeps, and nothing can hang the
 * build.
 */
class LogCaptureDrainTest {
    /**
     * A [LineSink] that records what reached "the ring", optionally holding
     * the capture thread inside its first append.
     */
    private class RecordingSink(private val holdFirstAppend: Boolean = false) : LineSink {
        val written = CopyOnWriteArrayList<String>()
        val firstAppendEntered = CountDownLatch(1)
        val releaseFirstAppend = CountDownLatch(1)

        override fun append(line: String) {
            written += line
            if (holdFirstAppend && written.size == 1) {
                firstAppendEntered.countDown()
                releaseFirstAppend.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }

        fun awaitSize(size: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (written.size < size && System.nanoTime() < deadline) Thread.onSpinWait()
            return written.size >= size
        }
    }

    private val deadlines = CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
    private val threads = CopyOnWriteArrayList<Thread>()
    private val sinces = CopyOnWriteArrayList<Long>()
    private val cleanups = CopyOnWriteArrayList<() -> Unit>()

    @After
    fun releaseEverything() {
        // Unblocks any capture thread a failing assertion left parked, so a
        // red test cannot leave threads behind for the next one.
        cleanups.forEach { it() }
    }

    /**
     * A [LogCapture] whose readers come from [logcats] in order, whose
     * sentinel is logged into the matching [FakeLogd] — one `logcat`, one
     * daemon view, per capture — and whose deadline and capture threads are
     * recorded for the test to drive and join.
     */
    private fun capture(
        sink: LineSink,
        logcats: List<FakeLogcat>,
    ): LogCapture {
        var nextReader = 0
        var nextEmit = 0
        logcats.forEach { logcat -> cleanups += { logcat.logd.pipe.eof() } }
        return LogCapture(
            ring = sink,
            readerFactory = { since ->
                sinces += since
                logcats[nextReader++].reader
            },
            emitSentinel = { sentinel -> logcats[nextEmit++].logd.log(sentinel, tag = "LogCapture") },
            armDeadline = { delay, onDeadline -> deadlines += delay to onDeadline },
            clock = { START_EPOCH_MILLIS },
            zone = { ZoneOffset.UTC },
            startThread = { body ->
                thread(name = CAPTURE_THREAD, isDaemon = true) { body() }.also { threads += it }
            },
        )
    }

    private fun capture(
        sink: LineSink,
        logcat: FakeLogcat,
    ) = capture(sink, listOf(logcat))

    /** Bounded wait for [n] deadlines to have been armed — R48 arms some on a capture thread. */
    private fun awaitDeadlines(n: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (deadlines.size < n && System.nanoTime() < deadline) Thread.onSpinWait()
        return deadlines.size >= n
    }

    /**
     * R18: every stop in this file goes through here, so a regression that
     * makes `stop()` wait on the capture fails the test instead of hanging it.
     */
    private fun stopPromptly(capture: LogCapture) {
        val returned = CountDownLatch(1)
        thread(name = "test-teardown", isDaemon = true) {
            capture.stop()
            returned.countDown()
        }
        assertTrue(
            "stop() blocked — teardown must never wait on log capture (R18)",
            returned.await(STOP_BOUND_MILLIS, TimeUnit.MILLISECONDS),
        )
    }

    private fun joinAll() {
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)) }
        threads.forEach { assertFalse("capture thread ${it.name} did not exit", it.isAlive) }
    }

    private fun line(body: String) = logcatLine("TunnelService", body)

    /** N1 mechanism (c). */
    @Test
    fun `lines already in the pipe at stop are all written, up to the sentinel`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink(holdFirstAppend = true)
        val capture = capture(sink, logcat)

        val bodies = (1..5).map { "teardown[step$it] exit +${it}ms" }
        bodies.forEach { logcat.logd.log(it) }
        logcat.logd.deliver()

        capture.start()
        // The capture thread is inside append(line 1) — redaction is ~1.3 ms a
        // line on device — with lines 2..5 still in the pipe behind it.
        assertTrue(sink.firstAppendEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        stopPromptly(capture)
        sink.releaseFirstAppend.countDown()
        logcat.logd.deliver()
        joinAll()

        assertEquals(bodies.map(::line), sink.written)
    }

    /**
     * Review I-1 / R47: mechanism (c) on the *deadline* path. The sentinel
     * never arrives; lines are still in the pipe behind a capture thread busy
     * writing line 1 when the deadline fires. The watchdog's SIGTERM lets
     * `logcat` exit with what it wrote still in the pipe, so every line
     * reaches the ring — followed by the deadline marker. At 6ea559a the
     * watchdog called `Process.destroy()`, which on Android closes our end of
     * the pipe and discards lines 2..5.
     */
    @Test
    fun `data still in the pipe at the deadline reaches the ring`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink(holdFirstAppend = true)
        val capture = capture(sink, logcat)

        val bodies = (1..5).map { "teardown[step$it] exit +${it}ms" }
        bodies.forEach { logcat.logd.log(it) }
        logcat.logd.deliver()

        capture.start()
        assertTrue(sink.firstAppendEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        stopPromptly(capture) // the sentinel is logged but never delivered
        assertTrue(awaitDeadlines(1))
        deadlines.single().second()
        sink.releaseFirstAppend.countDown()
        joinAll()

        assertEquals(bodies.map(::line), sink.written.dropLast(1))
        assertTrue("no deadline marker: ${sink.written}", "drain deadline" in sink.written.last())
        assertFalse("the watchdog destroyed logcat", logcat.process!!.destroyedBy == Thread.currentThread().name)
    }

    /** N1 mechanism (b): `done` is logged microseconds before stop, and delivered after it. */
    @Test
    fun `a line logged just before stop reaches the ring`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        assertTrue("capture never blocked in read", awaitParked(threads.single()))
        logcat.logd.log("teardown[lifecycle] done +113ms")
        stopPromptly(capture)
        logcat.logd.deliver()
        joinAll()

        assertEquals(listOf(line("teardown[lifecycle] done +113ms")), sink.written)
    }

    /** N1 mechanism (a): a session shorter than `logcat`'s startup delay. */
    @Test
    fun `a stop during logcat's startup delay still captures the session, up to the sentinel`() {
        val spawnEntered = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        cleanups += { releaseSpawn.countDown() }
        val logcat =
            FakeLogcat(FakeLogd()) {
                spawnEntered.countDown()
                releaseSpawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        assertTrue(spawnEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        logcat.logd.log("teardown[lifecycle] enter")
        logcat.logd.log("teardown[lifecycle] done +40ms")
        stopPromptly(capture)
        releaseSpawn.countDown()
        logcat.logd.deliver()
        joinAll()

        assertEquals(
            listOf(line("teardown[lifecycle] enter"), line("teardown[lifecycle] done +40ms")),
            sink.written,
        )
    }

    /**
     * R48: a capture's deadline runs from the *later* of its stop and its
     * spawn completing — a stop that lands while `logcat` is still spawning
     * must not start the clock on a process that does not exist yet.
     */
    @Test
    fun `a stop before spawn completes arms the deadline only once spawn completes`() {
        val spawnEntered = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        cleanups += { releaseSpawn.countDown() }
        val logcat =
            FakeLogcat(FakeLogd()) {
                spawnEntered.countDown()
                releaseSpawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        val capture = capture(RecordingSink(), logcat)

        capture.start()
        assertTrue(spawnEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        stopPromptly(capture)
        assertEquals("deadline armed before logcat had even spawned", 0, deadlines.size)

        releaseSpawn.countDown()
        assertTrue("deadline never armed after spawn", awaitDeadlines(1))
        logcat.logd.deliver()
        joinAll()
    }

    @Test
    fun `nothing after the sentinel is written, and the sentinel itself never is`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        logcat.logd.log("teardown[lifecycle] done +9ms")
        stopPromptly(capture)
        logcat.logd.log("the next session's first line")
        logcat.logd.deliver()
        joinAll()

        assertEquals(listOf(line("teardown[lifecycle] done +9ms")), sink.written)
        assertTrue("sentinel reached the ring", sink.written.none { logcat.reader.sentinel in it })
        assertEquals("the capture thread must end its own subprocess", CAPTURE_THREAD, logcat.process!!.destroyedBy)
        assertEquals("the capture thread must close its own reader", CAPTURE_THREAD, logcat.logd.pipe.closedBy)
    }

    @Test
    fun `lines drained after stop are still redacted`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        logcat.logd.log("dial failed to 203.0.113.44:443")
        stopPromptly(capture)
        logcat.logd.deliver()
        joinAll()

        assertEquals(1, sink.written.size)
        assertFalse("raw IP on disk: ${sink.written}", "203.0.113.44" in sink.written.single())
        assertTrue("tag lost: ${sink.written}", "TunnelService" in sink.written.single())
    }

    /**
     * The sentinel never arrives (a `logcat` still inside a startup delay
     * longer than the deadline, or one that died). The watchdog SIGTERMs
     * `logcat` at the deadline (R47) and the capture thread — never the
     * watchdog — drains, closes its reader, and exits. The ring then says so:
     * a deadline stop is abnormal by definition, so D2's suppression of a
     * stop-caused read failure does not hide it (review M-2).
     */
    @Test
    fun `without its sentinel the watchdog signals at the deadline, the capture exits, and the ring says so`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        logcat.logd.log("teardown[lifecycle] enter")
        logcat.logd.deliver()
        assertTrue(sink.awaitSize(1))
        stopPromptly(capture)
        // The sentinel is logged but never delivered.

        assertTrue(awaitDeadlines(1))
        val (delay, onDeadline) = deadlines.single()
        assertEquals(LogCapture.DRAIN_DEADLINE_MILLIS, delay)
        assertTrue(
            "the drain deadline must exceed logcat's measured startup delay (1.9–3.9 s)",
            delay > 3_900,
        )
        onDeadline()
        joinAll()

        assertEquals(2, sink.written.size)
        assertEquals(line("teardown[lifecycle] enter"), sink.written[0])
        assertTrue("no deadline marker: ${sink.written}", "drain deadline" in sink.written[1])
        assertEquals("the watchdog signals", listOf(Thread.currentThread().name), logcat.process!!.signalledBy)
        assertEquals(listOf(PipeProcess.FAKE_PID), logcat.signalled)
        assertEquals("…but only the capture thread closes the reader", CAPTURE_THREAD, logcat.logd.pipe.closedBy)
        assertFalse(logcat.reader.endedWithError)
    }

    /** R18: a wedged `logcat` — one that neither delivers nor exits on SIGTERM — never holds up teardown. */
    @Test
    fun `stop returns promptly even when logcat is stuck and never delivers`() {
        val logcat = FakeLogcat(FakeLogd(), exitsOnSigterm = false)
        val capture = capture(RecordingSink(), logcat)

        capture.start()
        assertTrue("capture never blocked in read", awaitParked(threads.single()))

        stopPromptly(capture)

        assertTrue(awaitDeadlines(1))
        val deadlineReturned = CountDownLatch(1)
        thread(name = "test-watchdog", isDaemon = true) {
            deadlines.single().second()
            deadlineReturned.countDown()
        }
        assertTrue(
            "the deadline callback blocked on a stuck logcat",
            deadlineReturned.await(STOP_BOUND_MILLIS, TimeUnit.MILLISECONDS),
        )
    }

    /**
     * I3's guarantee under R50 (amends R48): a new session can start while the
     * previous capture is still draining to its sentinel. The new `logcat` is
     * spawned only once the old capture thread has finished — serial readers,
     * because on the Pixel 8 a `-T` reader's cold start grew with the number
     * starting together (2.3 s for 1, 4.1 s for 2, 6.4–8.6 s for 4). What the
     * new session logs meanwhile is not lost: `-T <epoch>` replays it. The
     * ring holds the old session's tail, then the new session — no
     * interleaving.
     */
    @Test
    fun `a new capture spawns its logcat only after the old one has finished`() {
        val secondSpawned = CountDownLatch(1)
        val first = FakeLogcat(FakeLogd())
        val second = FakeLogcat(FakeLogd()) { secondSpawned.countDown() }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(first, second))

        capture.start()
        first.logd.log("old enter")
        first.logd.deliver()
        assertTrue(sink.awaitSize(1))
        first.logd.log("old done")
        stopPromptly(capture) // the old capture is now draining; its sentinel is not yet delivered

        capture.start()
        val newThread = threads[1]
        assertTrue("the new capture thread never parked", awaitParked(newThread))
        assertEquals("the new logcat spawned while the old capture drained (R50)", 1L, secondSpawned.count)
        second.logd.log("new enter") // logged before the new logcat exists; -T replays it
        second.logd.deliver()
        assertEquals(
            "the new capture wrote before the old one finished draining",
            listOf(line("old enter")),
            sink.written.toList(),
        )

        first.logd.deliver() // "old done", then the old sentinel
        assertTrue(
            "the new logcat never spawned once the old capture finished",
            secondSpawned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        assertTrue(sink.awaitSize(3))
        stopPromptly(capture)
        second.logd.deliver()
        joinAll()

        assertEquals(listOf(line("old enter"), line("old done"), line("new enter")), sink.written)
    }

    /**
     * Review I-2 / R48, kept under R50: a burst of fast-failing connects. Four
     * sessions start and stop in quick succession. Each capture's deadline
     * runs from its own spawn — so a capture queued behind its predecessor is
     * never armed, let alone killed, while it waits — and each spawns only
     * after its predecessor has finished. At 6ea559a the deadline ran from
     * the stop, and the queued sessions were killed cold and lost whole.
     */
    @Test
    fun `rapid start-stop cycles arm each deadline at its own spawn and capture every session`() {
        val spawned = (1..SESSIONS).map { CountDownLatch(1) }
        val logcats = spawned.map { latch -> FakeLogcat(FakeLogd()) { latch.countDown() } }
        val sink = RecordingSink()
        val capture = capture(sink, logcats)

        logcats.forEachIndexed { i, logcat ->
            capture.start()
            logcat.logd.log("session$i teardown[lifecycle] done +3ms")
            stopPromptly(capture)
        }

        logcats.forEachIndexed { i, logcat ->
            assertTrue("session $i never spawned", spawned[i].await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(awaitDeadlines(i + 1))
            assertEquals("a deadline was armed for a capture still queued", i + 1, deadlines.size)
            logcat.logd.deliver() // warm now: everything through its sentinel
        }
        joinAll()

        assertEquals(
            (0 until SESSIONS).map { line("session$it teardown[lifecycle] done +3ms") },
            sink.written,
        )
    }

    /**
     * N1.4 / N3, ruling R50. On the Pixel 8 a burst of 4 fast reconnects
     * captured 0 of 4 sessions, twice: R48 spawned each new `logcat` at once,
     * so four readers cold-started together, and a `-T` reader's cold start
     * grows with the number starting at once (2.3 s / 4.1 s / 6.4–8.6 s for
     * 1 / 2 / 4) — past the 5 s drain deadline. Here every fake `logcat` has a
     * cold start far longer than the burst, and at most one may be running at
     * any moment: each spawn must find every earlier capture's reader already
     * closed.
     */
    @Test
    fun `a burst of rapid start-stop cycles runs one logcat at a time and captures every session`() {
        val pump =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "test-logd-pump").apply { isDaemon = true }
            }
        cleanups += { pump.shutdownNow() }
        val overlapped = CopyOnWriteArrayList<Int>()
        val logcats = ArrayList<FakeLogcat>()
        repeat(SESSIONS) { i ->
            val logd = FakeLogd()
            logcats +=
                FakeLogcat(logd) {
                    if (logcats.take(i).any { it.logd.pipe.closedBy == null }) overlapped += i
                    // Cold: nothing for COLD_START_MILLIS, then warm and prompt.
                    pump.scheduleWithFixedDelay({ logd.deliver() }, COLD_START_MILLIS, 1, TimeUnit.MILLISECONDS)
                }
        }
        val sink = RecordingSink()
        val capture = capture(sink, logcats)

        logcats.forEachIndexed { i, logcat ->
            capture.start()
            logcat.logd.log("session$i teardown[lifecycle] done +3ms")
            stopPromptly(capture)
        }
        joinAll()

        assertEquals("these logcats spawned while an earlier capture was still running", emptyList<Int>(), overlapped)
        assertEquals(
            (0 until SESSIONS).map { line("session$it teardown[lifecycle] done +3ms") },
            sink.written,
        )
    }

    /**
     * Ruling R50: `-T <epoch>` is not exact — capture S20 on the Pixel 8
     * replayed 10 of S19's lines logged up to ~0.4 s before its epoch. A line
     * stamped before the start epoch is dropped; one in the same millisecond
     * or later is kept; so is anything without a parseable stamp (fail open).
     */
    @Test
    fun `a replayed line stamped before the capture's start epoch never reaches the ring`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)
        // START_EPOCH_MILLIS is 2024-09-10T20:26:40.123Z.
        val stale = "09-10 20:26:39.700  1234  5678 I TunnelService: teardown[lifecycle] done +88ms"
        val sameMillis = "09-10 20:26:40.123  1234  5678 I TunnelService: teardown[lifecycle] enter"

        capture.start()
        logcat.logd.pipe.feed("--------- beginning of main")
        logcat.logd.pipe.feed(stale)
        logcat.logd.pipe.feed(sameMillis)
        logcat.logd.log("tunnel started")
        stopPromptly(capture)
        logcat.logd.deliver()
        joinAll()

        assertEquals(3, sink.written.size)
        assertTrue("banner dropped: ${sink.written}", "beginning of main" in sink.written[0])
        assertEquals(sameMillis, sink.written[1])
        assertEquals(line("tunnel started"), sink.written[2])
    }

    /**
     * Review M-A / ruling R49: `logcat` exiting on its own mid-session — for
     * whatever reason, including logd dropping a reader that fell too far
     * behind — must not leave the ring silently truncated.
     */
    @Test
    fun `logcat exiting with no stop requested writes the unexpected-end marker`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        logcat.logd.log("teardown[lifecycle] enter")
        logcat.logd.deliver()
        logcat.logd.pipe.eof() // logcat exits; nobody asked it to
        threads.single().join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

        assertEquals(2, sink.written.size)
        assertEquals(line("teardown[lifecycle] enter"), sink.written[0])
        assertTrue("no unexpected-end marker: ${sink.written}", "ended unexpectedly" in sink.written[1])
        assertFalse(logcat.reader.endedWithError)
        stopPromptly(capture)
    }

    /** R49: an EOF that follows a requested stop is the stop working, not news. */
    @Test
    fun `logcat exiting after a requested stop writes no unexpected-end marker`() {
        val logcat = FakeLogcat(FakeLogd())
        val sink = RecordingSink()
        val capture = capture(sink, logcat)

        capture.start()
        logcat.logd.log("teardown[lifecycle] done +4ms")
        logcat.logd.deliver()
        assertTrue(sink.awaitSize(1))
        stopPromptly(capture)
        logcat.logd.pipe.eof() // exits before its sentinel is delivered
        joinAll()

        assertEquals(listOf(line("teardown[lifecycle] done +4ms")), sink.written)
    }

    /** N1 / R46 item 5: the `-T` epoch is the wall-clock reading taken inside [LogCapture.start]. */
    @Test
    fun `the reader follows logcat from the epoch taken in start`() {
        val logcat = FakeLogcat(FakeLogd())
        val capture = capture(RecordingSink(), logcat)

        capture.start()
        stopPromptly(capture)
        logcat.logd.deliver()
        joinAll()

        assertEquals(listOf(START_EPOCH_MILLIS), sinces)
    }

    private companion object {
        const val CAPTURE_THREAD = "subspace-log-capture"
        const val START_EPOCH_MILLIS = 1_726_000_000_123L
        const val TIMEOUT_SECONDS = 5L
        const val STOP_BOUND_MILLIS = 1_000L
        const val SESSIONS = 4
        const val COLD_START_MILLIS = 100L
    }
}
