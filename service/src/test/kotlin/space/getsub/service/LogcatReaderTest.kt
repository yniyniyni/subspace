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
import java.io.SequenceInputStream
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

    /**
     * A finished-output [Process]. Like the R47 spawn, its stdout starts with
     * the shell's PID line unless [pidLine] is false.
     */
    private class FakeProcess(
        output: InputStream,
        private val events: MutableList<String>? = null,
        pidLine: Boolean = true,
    ) : Process() {
        private val stream: InputStream =
            if (pidLine) SequenceInputStream(ByteArrayInputStream("4242\n".toByteArray()), output) else output

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

    /**
     * R47: `logcat` is spawned through a shell that prints its own PID and then
     * `exec`s `logcat` (same PID), so the watchdog can SIGTERM it without
     * `Process.destroy()`. `"$@"` keeps the arguments as separate argv
     * elements the shell never re-parses.
     */
    @Test
    fun `logcat is spawned through a shell that reports its PID and execs logcat with unparsed args`() {
        val command = logcatProcessBuilder(sinceEpochMillis = 1_726_000_000_123L).command()
        assertEquals(
            listOf("sh", "-c", "echo \$\$; exec logcat \"\$@\"", "sh", "-v", "threadtime", "-T", "1726000000.123"),
            command,
        )
    }

    @Test
    fun `the epoch argument zero-pads its milliseconds`() {
        val command = logcatProcessBuilder(sinceEpochMillis = 1_726_000_000_007L).command()
        assertEquals("1726000000.007", command[command.indexOf("-T") + 1])
    }

    /** R47: the shell's PID line (prepended by [FakeProcess]) is consumed, never emitted. */
    @Test
    fun `emits each line of the subprocess output, but not the shell's PID line`() {
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
     * Review I-1 / ruling R47. Android's `Process.destroy()` closes *our* end
     * of the pipe (`UNIXProcess.java:221-234`), discarding everything unread —
     * so the watchdog must never call it. It SIGTERMs `logcat` by PID instead:
     * `logcat` exits, what it wrote stays in the pipe, and the capture thread
     * drains to EOF and cleans up itself. The consumer is held inside line
     * `a` while `b` and `c` sit unread in the pipe, exactly when the deadline
     * fires. The reader is closed only by the capture thread (D3).
     */
    @Test
    fun `the deadline SIGTERMs logcat by PID, and what is still in the pipe is drained, not discarded`() {
        val pipe = LinePipe()
        val process = PipeProcess(pipe)
        val signalled = CopyOnWriteArrayList<Int>()
        val reader =
            LogcatReader(signal = { pid ->
                signalled += pid
                process.sigterm()
            }) { process }
        val inFirstLine = CountDownLatch(1)
        val releaseFirstLine = CountDownLatch(1)
        val captured = CopyOnWriteArrayList<String>()
        listOf("a", "b", "c").forEach(pipe::feed)
        val capture =
            thread(name = "reader-test-capture", isDaemon = true) {
                reader.lines().forEach {
                    captured.add(it)
                    if (captured.size == 1) {
                        inFirstLine.countDown()
                        releaseFirstLine.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        try {
            assertTrue(inFirstLine.await(5, TimeUnit.SECONDS))

            reader.requestStop()
            reader.forceStop()
            releaseFirstLine.countDown()
            capture.join(TimeUnit.SECONDS.toMillis(5))

            assertFalse("capture thread did not exit after the deadline", capture.isAlive)
            assertEquals(listOf("a", "b", "c"), captured)
            assertEquals(listOf(PipeProcess.FAKE_PID), signalled)
            assertEquals(listOf(Thread.currentThread().name), process.signalledBy)
            assertTrue(
                "the watchdog called destroy(), which discards the pipe on Android",
                process.destroyedBy != Thread.currentThread().name,
            )
            assertEquals("reader-test-capture", pipe.closedBy)
            assertFalse(reader.endedWithError)
            assertTrue(reader.endedAtDeadline)
        } finally {
            releaseFirstLine.countDown()
            pipe.eof()
        }
    }

    /** R47: the same PID-reuse guard the platform's own `destroy()` uses — never signal an exited process. */
    @Test
    fun `the deadline neither signals nor destroys a process that has already exited`() {
        val pipe = LinePipe()
        val process = PipeProcess(pipe)
        val reader = LogcatReader(signal = { process.sigterm() }) { process }
        val lines = reader.lines().iterator()
        pipe.feed("a")
        assertEquals("a", lines.next())

        process.sigterm() // logcat exits on its own
        reader.forceStop()

        assertEquals("only the test's own SIGTERM", 1, process.signalledBy.size)
        assertFalse("destroy() on the deadline path discards the pipe", process.destroyed)
        assertFalse(lines.hasNext())
    }

    /**
     * A deadline that fires before the shell has even printed its PID: there
     * is nothing to signal, and nothing past the PID line has been read, so
     * the watchdog falls back to `destroy()` to bound the leak.
     */
    @Test
    fun `a deadline before the PID is known destroys, so the capture cannot wait forever`() {
        val pipe = LinePipe(pidLine = null)
        val process = PipeProcess(pipe)
        val reader = LogcatReader(signal = { process.sigterm() }) { process }
        val captured = CopyOnWriteArrayList<String>()
        val capture =
            thread(name = "reader-test-capture", isDaemon = true) {
                reader.lines().forEach { captured.add(it) }
            }
        try {
            assertTrue(awaitParked(capture))
            reader.requestStop()
            reader.forceStop()
            capture.join(TimeUnit.SECONDS.toMillis(5))

            assertFalse(capture.isAlive)
            assertTrue(process.destroyed)
            assertEquals(emptyList<String>(), captured)
        } finally {
            pipe.eof()
        }
    }

    @Test
    fun `a capture that reaches its sentinel did not end at the deadline`() {
        lateinit var reader: LogcatReader
        reader =
            LogcatReader {
                FakeProcess(ByteArrayInputStream("x\n${logcatLine("LogCapture", reader.sentinel)}\n".toByteArray()))
            }
        assertEquals(listOf("x"), reader.lines().toList())
        reader.forceStop()
        assertFalse(reader.endedAtDeadline)
    }

    /** R47: a first line that is not a PID means the shell did not get as far as `exec logcat`. */
    @Test
    fun `a first line that is not a PID is a failed spawn`() {
        var spawned: FakeProcess? = null
        val reader =
            LogcatReader {
                FakeProcess(ByteArrayInputStream("sh: logcat: not found\nalpha\n".toByteArray()), pidLine = false)
                    .also { spawned = it }
            }

        assertEquals(emptyList<String>(), reader.lines().toList())
        assertEquals(true, spawned?.destroyed)
        assertFalse(reader.endedWithError)
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
                releaseSpawn.await(5, TimeUnit.SECONDS)
                FakeProcess(ByteArrayInputStream("line1\n".toByteArray())).also { spawned = it }
            }

        val captured = mutableListOf<String>()
        val captureThread =
            thread(name = "logcat-reader-test-capture", isDaemon = true) {
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
                releaseSpawn.await(5, TimeUnit.SECONDS)
                val text =
                    listOf("early", logcatLine("LogCapture", reader.sentinel), "late")
                        .joinToString("\n", postfix = "\n")
                FakeProcess(ByteArrayInputStream(text.toByteArray())).also { spawned = it }
            }

        val captured = mutableListOf<String>()
        val captureThread =
            thread(name = "logcat-reader-test-capture", isDaemon = true) {
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
}
