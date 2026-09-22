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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LogcatReaderTest {
    private class TrackingInputStream(private val data: ByteArray) : ByteArrayInputStream(data) {
        var isClosed = false
            private set

        override fun close() {
            isClosed = true
            super.close()
        }
    }

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
     * Review finding I2: without `-T`, `logcat` dumps this UID's whole
     * retained buffer before it starts following, duplicating pre-session
     * history into a ring the spec calls a record of one session. Asserts
     * against [logcatProcessBuilder]'s actual argument list — not a
     * hand-copied literal — so a future edit that drops the flag fails this
     * test rather than only showing up on a device days later.
     */
    @Test
    fun `the logcat command follows from the moment capture starts, not the buffer head`() {
        val command = logcatProcessBuilder().command()
        assertTrue("no -T flag: $command", "-T" in command)
        assertEquals("-T must be followed by its argument", "1", command[command.indexOf("-T") + 1])
    }

    @Test
    fun `emits each line of the subprocess output`() {
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream("alpha\nbravo\ncharlie\n".toByteArray())) }
        assertEquals(listOf("alpha", "bravo", "charlie"), reader.lines().toList())
    }

    @Test
    fun `close destroys the subprocess`() {
        var spawned: FakeProcess? = null
        val reader =
            LogcatReader {
                FakeProcess(ByteArrayInputStream("x\n".toByteArray())).also { spawned = it }
            }
        reader.lines().first()
        reader.close()
        assertEquals(true, spawned?.destroyed)
    }

    @Test
    fun `close closes the reader`() {
        var trackingStream: TrackingInputStream? = null
        val reader =
            LogcatReader {
                TrackingInputStream("x\n".toByteArray()).also { trackingStream = it }
                FakeProcess(trackingStream!!)
            }
        reader.lines().first()
        reader.close()
        assertEquals(true, trackingStream?.isClosed)
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
     * D2 (device verification, 2026-09-20): on one of two clean disconnects,
     * `LogCapture.stop` closed the reader while `readLine()` was still
     * blocked inside it. Both `BufferedReader.readLine()` and
     * `BufferedReader.close()` synchronize on the same monitor, so in
     * practice the two only race when the capture thread is between reads —
     * whichever one gets there first decides whether the *next* read sees a
     * closed stream (throwing `IOException("Stream closed")`) or a still-live
     * one. This test drives that outcome directly, without depending on JDK
     * `Reader` lock timing to land a genuinely concurrent read mid-flight:
     * [close] runs between two [LogcatReader.lines] reads, so the next read
     * fails exactly the way the losing side of the real race does, and what's
     * under test is what [LogcatReader] does with that failure — not the
     * scheduling that produces it.
     *
     * Before this fix, [endedWithError] could not tell that failure apart
     * from a genuine mid-stream break, so a normal disconnect accused itself
     * of a stream failure that never happened.
     */
    @Test
    fun `a stop we initiated does not set the error flag even when the next read fails`() {
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream("line1\n".toByteArray())) }
        val lines = reader.lines().iterator()
        assertEquals("line1", lines.next())

        reader.close()

        // The reader is now closed underneath the still-live sequence, which
        // is exactly the shape of the real race's losing branch.
        assertFalse("the closed stream unexpectedly yielded another line", lines.hasNext())
        assertFalse(reader.endedWithError)
    }

    /**
     * D3 (this branch, 2026-09-21): pins the *order* [close] performs its two
     * calls in, which is the actual fix — the subprocess must die before the
     * reader is closed, not after.
     *
     * This does not, and cannot, reproduce the deadlock itself: that requires a
     * thread genuinely blocked inside a native `readLine()` racing a `close()`
     * from a second thread, and a previous attempt at exactly that wedged the
     * test JVM and had to be `kill -9`'d (see the task brief this fix came
     * from). What this test *can* pin, deterministically and without spawning
     * a thread, is the one property that actually prevents the wedge: by the
     * time `reader.close()` runs, `process.destroy()` has already run. Revert
     * the order in [LogcatReader.close] and this test fails immediately — no
     * timing, no flakiness, no thread.
     */
    @Test
    fun `close destroys the subprocess before closing the reader`() {
        val events = mutableListOf<String>()
        var spawned: FakeProcess? = null
        val reader =
            LogcatReader {
                val stream = OrderTrackingInputStream("x\n".toByteArray(), events)
                FakeProcess(stream, events).also { spawned = it }
            }
        reader.lines().first()

        reader.close()

        assertEquals(true, spawned?.destroyed)
        assertEquals(listOf("process-destroyed", "reader-closed"), events)
    }

    /**
     * F3 (review, 2026-09-22): the actual regression this task fixes.
     *
     * Reproduces the reviewer's latch-driven probe through the same [spawn]
     * seam, deterministically and without any real `logcat` process or thread
     * sleep: two latches pin the capture thread inside [spawn] until [close]
     * has fully returned, so `close during spawn` is not a timing hope, it is
     * the only order this test can produce.
     *
     * Before the fix: [close] saw `process == null` and `reader == null`,
     * destroyed nothing, and returned; [lines] then published the process
     * [spawn] eventually handed back and started reading it, even though
     * [LogCapture.stop] had already discarded its own reference — exactly
     * the `close during spawn: destroyed=false, linesAfterClose=[still
     * reading after stop]` result the review recorded.
     */
    @Test
    fun `close during spawn destroys the process and yields no lines`() {
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
        reader.close()
        releaseSpawn.countDown()
        captureThread.join(TimeUnit.SECONDS.toMillis(5))

        assertFalse("capture thread did not finish — lines() did not return", captureThread.isAlive)
        assertEquals(true, spawned?.destroyed)
        assertEquals(emptyList<String>(), captured)
    }

    /**
     * F3: the other order the fix must cover — [close] with nothing spawned
     * yet at all, not merely in flight. [lines] must still destroy whatever
     * [spawn] eventually produces rather than publishing and reading it.
     */
    @Test
    fun `close before lines begins destroys the process spawn later produces`() {
        var spawned: FakeProcess? = null
        val reader =
            LogcatReader {
                FakeProcess(ByteArrayInputStream("line1\n".toByteArray())).also { spawned = it }
            }

        reader.close()
        val lines = reader.lines().toList()

        assertEquals(emptyList<String>(), lines)
        assertEquals(true, spawned?.destroyed)
    }
}
