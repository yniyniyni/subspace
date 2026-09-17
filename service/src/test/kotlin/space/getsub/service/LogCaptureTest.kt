// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.service.log.LogCapture
import space.getsub.service.log.LogRing
import space.getsub.service.log.LogcatReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LogCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Mirrors [LogcatReaderTest]'s fake — a [Process] whose input stream is fixed in advance. */
    private class FakeProcess(private val stream: InputStream) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stream

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    /** An input stream that throws partway through, to drive [LogcatReader.endedWithError]. */
    private class FailingInputStream(data: String) : InputStream() {
        private var position = 0
        private val bytes = data.toByteArray()

        override fun read(): Int {
            if (position >= bytes.size) {
                return -1
            }
            if (position == 10) {
                throw IOException("simulated mid-stream failure")
            }
            return bytes[position++].toInt()
        }
    }

    @Test
    fun `a server address never reaches the ring`() {
        val ring = LogRing(tmp.newFolder())
        LogCapture(ring).captureOnce(sequenceOf("dial failed to 203.0.113.44:443"))

        val written = ring.readAll().joinToString("\n")
        assertFalse("raw IP on disk: $written", "203.0.113.44" in written)
        assertTrue("line was dropped entirely", written.isNotEmpty())
    }

    @Test
    fun `a UUID never reaches the ring`() {
        val ring = LogRing(tmp.newFolder())
        LogCapture(ring).captureOnce(
            sequenceOf("user id 3f2504e0-4f89-11d3-9a0c-0305e82c3301 rejected"),
        )

        val written = ring.readAll().joinToString("\n")
        assertFalse(written, "3f2504e0-4f89-11d3-9a0c-0305e82c3301" in written)
    }

    @Test
    fun `a subscription URL never reaches the ring`() {
        val ring = LogRing(tmp.newFolder())
        LogCapture(ring).captureOnce(sequenceOf("fetching https://panel.example.com/sub/abc"))

        val written = ring.readAll().joinToString("\n")
        assertFalse(written, "panel.example.com" in written)
    }

    @Test
    fun `a diagnostic with no secrets survives intact`() {
        val ring = LogRing(tmp.newFolder())
        // M8's teardown instrumentation is exactly this shape, and it is the
        // line W7 depends on (spec §5).
        val line = "stopTunnel: phase=tun2socks-stop"
        LogCapture(ring).captureOnce(sequenceOf(line))

        val written = ring.readAll()
        assertTrue("expected \"$line\" verbatim, got $written", written.any { line in it })
    }

    @Test
    fun `redaction is idempotent across a second pass`() {
        val ring = LogRing(tmp.newFolder())
        val capture = LogCapture(ring)
        capture.captureOnce(sequenceOf("dial failed to 203.0.113.44:443"))
        val once = ring.readAll().single()

        ring.clear()
        capture.captureOnce(sequenceOf(once))
        assertTrue(ring.readAll().single() == once)
    }

    @Test
    fun `an abnormal end appends the marker`() {
        val ring = LogRing(tmp.newFolder())
        val reader = LogcatReader { FakeProcess(FailingInputStream("0123456789abcdefghij")) }

        LogCapture(ring).captureOnce(reader)

        assertTrue(reader.endedWithError)
        assertTrue(ring.readAll().any { "ended abnormally" in it })
    }

    @Test
    fun `a clean end does not append the marker`() {
        val ring = LogRing(tmp.newFolder())
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream("line1\nline2\n".toByteArray())) }

        LogCapture(ring).captureOnce(reader)

        assertFalse(reader.endedWithError)
        assertTrue(ring.readAll().none { "ended abnormally" in it })
    }
}
