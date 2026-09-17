// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.service.log.LogcatReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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

    private class FakeProcess(private val stream: InputStream) : Process() {
        var destroyed = false
            private set

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stream

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() {
            destroyed = true
        }
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
}
