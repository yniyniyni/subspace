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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class LogCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * [LogRing.append] is the only production surface left on that class
     * (review finding M2 — `readAll` was a test-only accessor and was
     * deleted), so these tests read `log.0`/`log.1` back directly, the same
     * two file names [LogRing] itself writes to.
     */
    private fun readAll(dir: File): String =
        listOf(File(dir, "log.1"), File(dir, "log.0"))
            .filter { it.exists() }
            .flatMap { it.readLines() }
            .joinToString("\n")

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

    /** A `-v threadtime` line, the real shape `LogcatReader.lines()` emits. */
    private fun logcatLine(
        tag: String,
        body: String,
    ) = "09-17 19:40:00.123  1234  5678 W $tag: $body"

    @Test
    fun `a server address in a realistically-prefixed line never reaches the ring`() {
        val dir = tmp.newFolder()
        val line = logcatLine("TunnelService", "dial failed to 203.0.113.44:443")
        LogCapture(LogRing(dir)).captureOnce(sequenceOf(line))

        val written = readAll(dir)
        assertFalse("raw IP on disk: $written", "203.0.113.44" in written)
        assertTrue("tag was lost along with the secret: $written", "TunnelService" in written)
    }

    @Test
    fun `a UUID in a realistically-prefixed line never reaches the ring`() {
        val dir = tmp.newFolder()
        val line = logcatLine("AuthWorker", "user id 3f2504e0-4f89-11d3-9a0c-0305e82c3301 rejected")
        LogCapture(LogRing(dir)).captureOnce(sequenceOf(line))

        val written = readAll(dir)
        assertFalse(written, "3f2504e0-4f89-11d3-9a0c-0305e82c3301" in written)
        assertTrue("tag was lost along with the secret: $written", "AuthWorker" in written)
    }

    @Test
    fun `a subscription URL in a realistically-prefixed line never reaches the ring`() {
        val dir = tmp.newFolder()
        val line = logcatLine("SubFetcher", "fetching https://panel.example.com/sub/abc")
        LogCapture(LogRing(dir)).captureOnce(sequenceOf(line))

        val written = readAll(dir)
        assertFalse(written, "panel.example.com" in written)
        assertTrue("tag was lost along with the secret: $written", "SubFetcher" in written)
    }

    @Test
    fun `a realistically-shaped logcat line keeps its tag and phase value`() {
        val dir = tmp.newFolder()
        // M8's teardown instrumentation is exactly this shape, and the tag and
        // the phase value are what W7 depends on (spec §5) — not byte-identity
        // of the whole line. The body's own leading "stopTunnel:" label is
        // redact()'s documented fail-safe direction (BARE_HOST_PREFIX_PATTERN
        // treats any leading "word:" as a candidate bare host) and is accepted,
        // not chased: the component name survives in the tag, and the value
        // survives in "phase=tun2socks-stop".
        val line = logcatLine("TunnelService", "stopTunnel: phase=tun2socks-stop")
        LogCapture(LogRing(dir)).captureOnce(sequenceOf(line))

        val written = readAll(dir)
        assertTrue("tag lost: $written", "TunnelService" in written)
        assertTrue("phase value lost: $written", "phase=tun2socks-stop" in written)
    }

    @Test
    fun `a line that does not match the logcat shape is redacted whole`() {
        val dir = tmp.newFolder()
        // Missing the pid/tid fields the real prefix always carries, so this
        // must fail LOGCAT_PREFIX_PATTERN and fall back to whole-line redact().
        // If capture ever regressed to leaving an unparseable line alone
        // instead, the IP below would survive verbatim.
        val line = "09-17 19:40:00.123 W TunnelService: dial failed to 203.0.113.44:443"
        LogCapture(LogRing(dir)).captureOnce(sequenceOf(line))

        val written = readAll(dir)
        assertFalse("raw IP on disk after a fail-closed line: $written", "203.0.113.44" in written)
    }

    @Test
    fun `an embedded newline in the tag position does not leak the secret that follows it`() {
        val dir = tmp.newFolder()
        // The tag group is a negated character class, which matches \n and \r
        // regardless of DOTALL. Without excluding them explicitly, a "line"
        // whose first physical line has no colon lets the tag group run past
        // the newline and swallow real content — including a secret — into
        // the prefix this class preserves verbatim. Captured lines never
        // legitimately contain an embedded newline (LogcatReader.lines() uses
        // BufferedReader.readLine(), which strips every terminator), but this
        // seam (captureOnce(Sequence<String>)) accepts arbitrary strings, and
        // the guard belongs to this class, not to what a caller happens to do.
        val line = "09-17 19:40:00.123  1234  5678 W Tag no colon\n203.0.113.44 secret: rest"
        LogCapture(LogRing(dir)).captureOnce(sequenceOf(line))

        val written = readAll(dir)
        assertFalse("raw IP leaked through the tag group: $written", "203.0.113.44" in written)
    }

    @Test
    fun `redaction is idempotent across a second pass on a realistically-prefixed line`() {
        val dir = tmp.newFolder()
        val capture = LogCapture(LogRing(dir))
        val line = logcatLine("TunnelService", "dial failed to 203.0.113.44:443")
        capture.captureOnce(sequenceOf(line))
        val once = readAll(dir)

        // A fresh directory for the second pass, rather than LogRing.clear()
        // (deleted — review finding M2, no production caller): the property
        // under test is that redacting an already-redacted line is a no-op,
        // which needs an empty ring to write the second pass into, not
        // specifically this ring's own clear behaviour.
        val secondDir = tmp.newFolder()
        LogCapture(LogRing(secondDir)).captureOnce(sequenceOf(once))
        assertTrue(readAll(secondDir) == once)
    }

    @Test
    fun `an abnormal end appends the marker`() {
        val dir = tmp.newFolder()
        val reader = LogcatReader { FakeProcess(FailingInputStream("4242\n0123456789abcdefghij")) }

        LogCapture(LogRing(dir)).captureOnce(reader)

        assertTrue(reader.endedWithError)
        assertTrue("ended abnormally" in readAll(dir))
    }

    @Test
    fun `a clean end does not append the marker`() {
        val dir = tmp.newFolder()
        val reader = LogcatReader { FakeProcess(ByteArrayInputStream("4242\nline1\nline2\n".toByteArray())) }

        LogCapture(LogRing(dir)).captureOnce(reader)

        assertFalse(reader.endedWithError)
        assertFalse("ended abnormally" in readAll(dir))
    }
}
