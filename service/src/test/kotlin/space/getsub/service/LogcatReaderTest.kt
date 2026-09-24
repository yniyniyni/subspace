// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.service.log.LogcatReader
import space.getsub.service.log.logcatProcessBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LogcatReaderTest {
    private class FailingInputStream(private val data: String) : InputStream() {
        private var position = 0
        private val bytes = data.toByteArray()
        var isClosed = false
            private set

        override fun read(): Int {
            if (position >= bytes.size) {
                return -1
            }
            if (position == 10) {
                throw IOException("simulated mid-stream failure")
            }
            return bytes[position++].toInt()
        }

        override fun close() {
            isClosed = true
            super.close()
        }
    }

    private class FakeProcess(
        private val stream: InputStream,
        private val events: MutableList<String>? = null,
    ) : Process() {
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stream

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyed = true
            events?.add("process-destroyed")
        }
    }

    /** Records when the underlying stream is closed, so [close]'s call order can be pinned. */
    private class OrderTrackingInputStream(
        data: ByteArray,
        private val events: MutableList<String>,
    ) : ByteArrayInputStream(data) {
        override fun close() {
            events.add("reader-closed")
            super.close()
        }
    }

    /**
     * N1 / R46 (replaces I2's `-T 1`): `-T` takes an epoch — the moment
     * [space.getsub.service.log.LogCapture.start] ran — in `logcat`'s
     * `<seconds>.<millis>` form, so the capture replays nothing that predates
     * the session (I2's residual: `-T 1` always opened with the previous
     * session's last line) and first delivery is faster (~1.9 s vs ~3.9 s
     * measured on a Pixel 8). Asserts against [logcatProcessBuilder]'s actual
     * argument list, not a hand-copied literal.
     */
    @Test
    fun `the logcat command follows from the capture's start epoch, in seconds dot millis`() {
        val command = logcatProcessBuilder(sinceEpochMillis = 1_726_000_000_123L).command()
        assertTrue("no -T flag: $command", "-T" in command)
        val since = command[command.indexOf("-T") + 1]
        assertTrue("not <seconds>.<millis>: $since", Regex("""^\d+\.\d{3}$""").matches(since))
        assertEquals("1726000000.123", since)
    }

    @Test
    fun `the epoch argument zero-pads its milliseconds`() {
        val command = logcatProcessBuilder(sinceEpochMillis = 1_726_000_000_007L).command()
        assertEquals("1726000000.007", command[command.indexOf("-T") + 1])
    }

    @Test
    fun `emits each line of the subprocess output`() {
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream("alpha\nbravo\ncharlie\n".toByteArray())) }
        assertEquals(listOf("alpha", "bravo", "charlie"), reader.lines().toList())
    }

    /**
     * N1 / R46: the capture ends at *its own* sentinel — matched on the raw
     * line — and neither the sentinel nor anything after it is emitted.
     */
    @Test
    fun `lines end at this reader's own sentinel, which is never emitted`() {
        lateinit var reader: LogcatReader
        reader =
            LogcatReader {
                val text =
                    listOf(
                        logcatLine("TunnelService", "teardown[lifecycle] done +12ms"),
                        logcatLine("LogCapture", reader.sentinel),
                        logcatLine("TunnelService", "after the sentinel"),
                    ).joinToString("\n", postfix = "\n")
                FakeProcess(ByteArrayInputStream(text.toByteArray()))
            }

        val lines = reader.lines().toList()

        assertEquals(listOf(logcatLine("TunnelService", "teardown[lifecycle] done +12ms")), lines)
        assertFalse(reader.endedWithError)
    }

    /**
     * Two readers' sentinels share a constant prefix. A *different* capture's
     * sentinel (a `-T <epoch>` landing on the same millisecond as the previous
     * session's stop can replay it) is dropped — it is capture plumbing, not
     * diagnostic content — but it must not end *this* capture.
     */
    @Test
    fun `another capture's sentinel is dropped but does not end this one`() {
        val other = LogcatReader { error("never spawned") }
        val text =
            listOf(
                logcatLine("LogCapture", other.sentinel),
                "still reading",
            ).joinToString("\n", postfix = "\n")
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream(text.toByteArray())) }

        assertEquals(listOf("still reading"), reader.lines().toList())
    }

    @Test
    fun `sentinels are unique per reader`() {
        val a = LogcatReader { error("never spawned") }
        val b = LogcatReader { error("never spawned") }
        assertTrue(a.sentinel != b.sentinel)
    }

    /**
     * On its sentinel the capture thread itself destroys the subprocess and
     * then closes its own reader — D3's destroy-before-close order kept,
     * though with only the reading thread ever closing, the deadlock it
     * guarded against no longer has a second thread to happen between.
     */
    @Test
    fun `at the sentinel the reading thread destroys the subprocess, then closes its own reader`() {
        val events = mutableListOf<String>()
        lateinit var reader: LogcatReader
        reader =
            LogcatReader {
                val text = listOf("x", logcatLine("LogCapture", reader.sentinel)).joinToString("\n", postfix = "\n")
                FakeProcess(OrderTrackingInputStream(text.toByteArray(), events), events)
            }

        assertEquals(listOf("x"), reader.lines().toList())

        assertEquals(listOf("process-destroyed", "reader-closed"), events)
    }

    /**
     * R46 item 4 — the D3 guard under the new design. The watchdog's
     * [LogcatReader.forceStop] may only destroy the subprocess; the reader is
     * closed by the capture thread once the resulting EOF reaches it, never by
     * the watchdog's thread. A close from any other thread is what deadlocked
     * teardown in D3.
     */
    @Test
    fun `the deadline destroys the subprocess and never closes the reader from its own thread`() {
        val pipe = LinePipe()
        val process = PipeProcess(pipe)
        val reader = LogcatReader { process }
        val captured = CopyOnWriteArrayList<String>()
        val capture =
            thread(name = "reader-test-capture", isDaemon = true) {
                reader.lines().forEach { captured.add(it) }
            }
        try {
            pipe.feed("before the deadline")
            assertTrue("capture thread never blocked in read", awaitParkedAfter(capture) { captured.size == 1 })

            reader.requestStop()
            reader.forceStop()
            capture.join(TimeUnit.SECONDS.toMillis(5))

            assertFalse("capture thread did not exit after the deadline", capture.isAlive)
            assertEquals(Thread.currentThread().name, process.destroyedBy)
            assertEquals("reader-test-capture", pipe.closedBy)
            assertEquals(listOf("before the deadline"), captured)
            assertFalse(reader.endedWithError)
        } finally {
            pipe.eof()
        }
    }

    @Test
    fun `a spawn failure yields no lines rather than throwing`() {
        val reader = LogcatReader { error("no logcat on this device") }
        assertEquals(emptyList<String>(), reader.lines().toList())
    }

    @Test
    fun `clean end of stream does not set error flag`() {
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream("line1\nline2\n".toByteArray())) }
        reader.lines().toList()
        assertFalse(reader.endedWithError)
    }

    @Test
    fun `mid-stream read failure sets error flag`() {
        val reader = LogcatReader { FakeProcess(FailingInputStream("0123456789abcdefghij")) }
        reader.lines().toList()
        assertTrue(reader.endedWithError)
    }

    /**
     * D2 (device verification, 2026-09-20), adapted to N1 / R46. Originally:
     * `LogCapture.stop` closed the reader underneath an in-flight read, and
     * the next read's `IOException("Stream closed")` accused a normal
     * disconnect of a stream failure. Under R46 nothing but the capture
     * thread closes the reader, but a stop still *causes* the stream to end —
     * the watchdog's destroy can surface as a read failure rather than a
     * clean EOF — so the guarantee is unchanged: a read failure that follows
     * a requested stop is expected and does not set [LogcatReader.endedWithError].
     * [FailingInputStream] throws on the read after `line1`, which is exactly
     * the losing branch of that race, driven deterministically.
     */
    @Test
    fun `a read failure after a requested stop does not set the error flag`() {
        val reader = LogcatReader { FakeProcess(FailingInputStream("line1\nabcdefghij")) }
        val lines = reader.lines().iterator()
        assertEquals("line1", lines.next())

        reader.requestStop()

        assertFalse("the failed stream unexpectedly yielded another line", lines.hasNext())
        assertFalse(reader.endedWithError)
    }

    /**
     * F3 (review, 2026-09-22), adapted to N1 / R46. The original guarantee:
     * a stop landing while `logcat` is still spawning must not leak the
     * subprocess. Under R46 a *requested* stop no longer kills anything — the
     * capture drains to its sentinel — so the leak guard moved to the
     * deadline: once [LogcatReader.forceStop] has run, a process [spawn]
     * publishes afterwards is destroyed at once and never read. Two latches
     * pin the capture thread inside spawn until the deadline has fully run,
     * so the order is the only one this test can produce.
     */
    @Test
    fun `a deadline during spawn destroys the process and yields no lines`() {
        val spawnEntered = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        var spawned: FakeProcess? = null

        val reader =
            LogcatReader {
                spawnEntered.countDown()
                releaseSpawn.await()
                FakeProcess(ByteArrayInputStream("line1\n".toByteArray())).also { spawned = it }
            }

        val captured = mutableListOf<String>()
        val captureThread =
            thread(name = "logcat-reader-test-capture") {
                reader.lines().forEach { captured.add(it) }
            }

        assertTrue("spawn never started", spawnEntered.await(5, TimeUnit.SECONDS))
        reader.requestStop()
        reader.forceStop()
        releaseSpawn.countDown()
        captureThread.join(TimeUnit.SECONDS.toMillis(5))

        assertFalse("capture thread did not finish — lines() did not return", captureThread.isAlive)
        assertEquals(true, spawned?.destroyed)
        assertEquals(emptyList<String>(), captured)
    }

    /** F3: the deadline having passed before [LogcatReader.lines] even begins. */
    @Test
    fun `a deadline before lines begins destroys the process spawn later produces`() {
        var spawned: FakeProcess? = null
        val reader =
            LogcatReader {
                FakeProcess(ByteArrayInputStream("line1\n".toByteArray())).also { spawned = it }
            }

        reader.requestStop()
        reader.forceStop()
        val lines = reader.lines().toList()

        assertEquals(emptyList<String>(), lines)
        assertEquals(true, spawned?.destroyed)
    }

    /**
     * N1 mechanism (a) at the reader: a stop that lands during `logcat`'s
     * startup delay — here, still inside spawn — must still read what the
     * subprocess delivers once it is up, through to the sentinel. Before
     * R46 this was F3's "yields no lines", which is precisely how every
     * session shorter than ~1.5 s captured nothing.
     */
    @Test
    fun `a stop requested during spawn still reads up to the sentinel`() {
        val spawnEntered = CountDownLatch(1)
        val releaseSpawn = CountDownLatch(1)
        lateinit var reader: LogcatReader
        var spawned: FakeProcess? = null
        reader =
            LogcatReader {
                spawnEntered.countDown()
                releaseSpawn.await()
                val text =
                    listOf("early", logcatLine("LogCapture", reader.sentinel), "late")
                        .joinToString("\n", postfix = "\n")
                FakeProcess(ByteArrayInputStream(text.toByteArray())).also { spawned = it }
            }

        val captured = mutableListOf<String>()
        val captureThread =
            thread(name = "logcat-reader-test-capture") {
                reader.lines().forEach { captured.add(it) }
            }

        assertTrue("spawn never started", spawnEntered.await(5, TimeUnit.SECONDS))
        reader.requestStop()
        releaseSpawn.countDown()
        captureThread.join(TimeUnit.SECONDS.toMillis(5))

        assertFalse("capture thread did not finish", captureThread.isAlive)
        assertEquals(listOf("early"), captured)
        assertEquals(true, spawned?.destroyed)
    }

    /** Waits until [done] holds and [thread] is parked (blocked in its next read). */
    private fun awaitParkedAfter(
        thread: Thread,
        done: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done() && System.nanoTime() < deadline) Thread.onSpinWait()
        return done() && awaitParked(thread)
    }
}
