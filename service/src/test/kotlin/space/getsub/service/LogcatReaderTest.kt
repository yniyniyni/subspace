// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.service.log.LogcatReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class LogcatReaderTest {
    private class FakeProcess(private val text: String) : Process() {
        private val stream = ByteArrayInputStream(text.toByteArray())
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
        val reader = LogcatReader { FakeProcess("alpha\nbravo\ncharlie\n") }
        assertEquals(listOf("alpha", "bravo", "charlie"), reader.lines().toList())
    }

    @Test
    fun `close destroys the subprocess`() {
        var spawned: FakeProcess? = null
        val reader =
            LogcatReader {
                FakeProcess("x\n").also { spawned = it }
            }
        reader.lines().first()
        reader.close()
        assertEquals(true, spawned?.destroyed)
    }

    @Test
    fun `a spawn failure yields no lines rather than throwing`() {
        val reader = LogcatReader { error("no logcat on this device") }
        assertEquals(emptyList<String>(), reader.lines().toList())
    }
}
