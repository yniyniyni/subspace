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
import space.getsub.service.log.LogcatReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
     * A [LogCapture] whose readers come from [readers] in order, whose
     * sentinel is logged into [logds] in the same order — one `logcat`, one
     * daemon view, per capture — and whose deadline and capture threads are
     * recorded for the test to drive and join.
     */
    private fun capture(
        sink: LineSink,
        readers: List<LogcatReader>,
        logds: List<FakeLogd>,
    ): LogCapture {
        var nextReader = 0
        var nextEmit = 0
        logds.forEach { logd -> cleanups += { logd.pipe.eof() } }
        return LogCapture(
            ring = sink,
            readerFactory = { since ->
                sinces += since
                readers[nextReader++]
            },
            emitSentinel = { sentinel -> logds[nextEmit++].log(sentinel, tag = "LogCapture") },
            armDeadline = { delay, onDeadline -> deadlines += delay to onDeadline },
            clock = { START_EPOCH_MILLIS },
            startThread = { body ->
                thread(name = CAPTURE_THREAD, isDaemon = true) { body() }.also { threads += it }
            },
        )
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
        val logd = FakeLogd()
        val reader = LogcatReader { PipeProcess(logd.pipe) }
        val sink = RecordingSink(holdFirstAppend = true)
        val capture = capture(sink, listOf(reader), listOf(logd))

        val bodies = (1..5).map { "teardown[step$it] exit +${it}ms" }
        bodies.forEach { logd.log(it) }
        logd.deliver()

        capture.start()
        // The capture thread is inside append(line 1) — redaction is ~1.3 ms a
        // line on device — with lines 2..5 still in the pipe behind it.
        assertTrue(sink.firstAppendEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        stopPromptly(capture)
        sink.releaseFirstAppend.countDown()
        logd.deliver()
        joinAll()

        assertEquals(bodies.map(::line), sink.written)
    }

    /** N1 mechanism (b): `done` is logged microseconds before stop, and delivered after it. */
    @Test
    fun `a line logged just before stop reaches the ring`() {
        val logd = FakeLogd()
        val reader = LogcatReader { PipeProcess(logd.pipe) }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(reader), listOf(logd))

        capture.start()
        assertTrue("capture never blocked in read", awaitParked(threads.single()))
        logd.log("teardown[lifecycle] done +113ms")
        stopPromptly(capture)
        logd.deliver()
        joinAll()

        assertEquals(listOf(line("teardown[lifecycle] done +113ms")), sink.written)
    }

    /** N1 mechanism (a): a session shorter than `logcat`'s startup delay. */
    @Test
    fun `a stop during logcat's startup delay still captures the session, up to the sentinel`() {
        val logd = FakeLogd()
        val spawnEntered = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        cleanups += { releaseSpawn.countDown() }
        val reader =
            LogcatReader {
                spawnEntered.countDown()
                releaseSpawn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                PipeProcess(logd.pipe)
            }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(reader), listOf(logd))

        capture.start()
        assertTrue(spawnEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        logd.log("teardown[lifecycle] enter")
        logd.log("teardown[lifecycle] done +40ms")
        stopPromptly(capture)
        releaseSpawn.countDown()
        logd.deliver()
        joinAll()

        assertEquals(
            listOf(line("teardown[lifecycle] enter"), line("teardown[lifecycle] done +40ms")),
            sink.written,
        )
    }

    @Test
    fun `nothing after the sentinel is written, and the sentinel itself never is`() {
        val logd = FakeLogd()
        val process = PipeProcess(logd.pipe)
        val reader = LogcatReader { process }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(reader), listOf(logd))

        capture.start()
        logd.log("teardown[lifecycle] done +9ms")
        stopPromptly(capture)
        logd.log("the next session's first line")
        logd.deliver()
        joinAll()

        assertEquals(listOf(line("teardown[lifecycle] done +9ms")), sink.written)
        assertTrue("sentinel reached the ring", sink.written.none { reader.sentinel in it })
        assertEquals("the capture thread must end its own subprocess", CAPTURE_THREAD, process.destroyedBy)
        assertEquals("the capture thread must close its own reader", CAPTURE_THREAD, logd.pipe.closedBy)
    }

    @Test
    fun `lines drained after stop are still redacted`() {
        val logd = FakeLogd()
        val reader = LogcatReader { PipeProcess(logd.pipe) }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(reader), listOf(logd))

        capture.start()
        logd.log("dial failed to 203.0.113.44:443")
        stopPromptly(capture)
        logd.deliver()
        joinAll()

        assertEquals(1, sink.written.size)
        assertFalse("raw IP on disk: ${sink.written}", "203.0.113.44" in sink.written.single())
        assertTrue("tag lost: ${sink.written}", "TunnelService" in sink.written.single())
    }

    /**
     * The sentinel never arrives (a `logcat` still inside a startup delay
     * longer than the deadline, or one that died). The watchdog destroys the
     * subprocess at the deadline and the capture thread — never the watchdog —
     * drains, closes its reader, and exits.
     */
    @Test
    fun `if the sentinel never arrives the watchdog destroys at the deadline and the capture exits`() {
        val logd = FakeLogd()
        val process = PipeProcess(logd.pipe)
        val reader = LogcatReader { process }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(reader), listOf(logd))

        capture.start()
        logd.log("teardown[lifecycle] enter")
        logd.deliver()
        assertTrue(sink.awaitSize(1))
        stopPromptly(capture)
        // The sentinel is logged but never delivered.

        val (delay, onDeadline) = deadlines.single()
        assertEquals(LogCapture.DRAIN_DEADLINE_MILLIS, delay)
        assertTrue(
            "the drain deadline must exceed logcat's measured startup delay (1.9–3.9 s)",
            delay > 3_900,
        )
        onDeadline()
        joinAll()

        assertEquals(listOf(line("teardown[lifecycle] enter")), sink.written)
        assertEquals("the watchdog destroys", Thread.currentThread().name, process.destroyedBy)
        assertEquals("…but only the capture thread closes the reader", CAPTURE_THREAD, logd.pipe.closedBy)
        assertFalse(reader.endedWithError)
    }

    /** R18: a wedged `logcat` — one that neither delivers nor dies — never holds up teardown. */
    @Test
    fun `stop returns promptly even when logcat is stuck and never delivers`() {
        val logd = FakeLogd()
        val reader = LogcatReader { PipeProcess(logd.pipe, eofOnDestroy = false) }
        val sink = RecordingSink()
        val capture = capture(sink, listOf(reader), listOf(logd))

        capture.start()
        assertTrue("capture never blocked in read", awaitParked(threads.single()))

        stopPromptly(capture)

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
     * I3's guarantee under R46: a new session can start while the previous
     * capture is still draining to its sentinel. The new capture thread waits
     * for the old one before writing anything, so the ring holds the old
     * session's tail, then the new session — no interleaving, no gap.
     */
    @Test
    fun `a new capture started while the old one drains writes nothing until the old one has finished`() {
        val first = FakeLogd()
        val second = FakeLogd()
        val sink = RecordingSink()
        val capture =
            capture(
                sink,
                listOf(LogcatReader { PipeProcess(first.pipe) }, LogcatReader { PipeProcess(second.pipe) }),
                listOf(first, second),
            )

        capture.start()
        first.log("old enter")
        first.deliver()
        assertTrue(sink.awaitSize(1))
        first.log("old done")
        stopPromptly(capture) // the old capture is now draining; its sentinel is not yet delivered

        capture.start()
        second.log("new enter")
        second.deliver()
        val newThread = threads[1]
        assertTrue("the new capture thread never parked", awaitParked(newThread))
        assertEquals(
            "the new capture wrote before the old one finished draining",
            listOf(line("old enter")),
            sink.written.toList(),
        )

        first.deliver() // "old done", then the old sentinel
        assertTrue(sink.awaitSize(3))
        stopPromptly(capture)
        second.deliver()
        joinAll()

        assertEquals(listOf(line("old enter"), line("old done"), line("new enter")), sink.written)
    }

    /** N1 / R46 item 5: the `-T` epoch is the wall-clock reading taken inside [LogCapture.start]. */
    @Test
    fun `the reader follows logcat from the epoch taken in start`() {
        val logd = FakeLogd()
        val sink = RecordingSink()
        val capture = capture(sink, listOf(LogcatReader { PipeProcess(logd.pipe) }), listOf(logd))

        capture.start()
        stopPromptly(capture)
        logd.deliver()
        joinAll()

        assertEquals(listOf(START_EPOCH_MILLIS), sinces)
    }

    private companion object {
        const val CAPTURE_THREAD = "subspace-log-capture"
        const val START_EPOCH_MILLIS = 1_726_000_000_123L
        const val TIMEOUT_SECONDS = 5L
        const val STOP_BOUND_MILLIS = 1_000L
    }
}
