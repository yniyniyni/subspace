// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.service.log.PacedInputStream
import java.io.ByteArrayInputStream

/**
 * Row 7 (`docs/agent/research/2026-09-26-m8.5-row7-release.md`, Pass 5): logcat
 * writes one line per pipe write, so reading as fast as lines arrive costs a `read`
 * system call, a decode and an `available` probe per line. Pacing the reads lets the
 * pipe fill, so each read takes a batch.
 */
class PacedInputStreamTest {
    private class FakeTime {
        var nowMillis = 1_000L
        val sleeps = mutableListOf<Long>()

        fun sleep(millis: Long) {
            sleeps += millis
            nowMillis += millis
        }
    }

    /** A pipe that holds less than each read asks for: 5 bytes per read. */
    private class Trickle : java.io.InputStream() {
        override fun read(): Int = 'x'.code

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = minOf(len, 5).also { n -> b.fill('x'.code.toByte(), off, off + n) }
    }

    private fun paced(
        time: FakeTime,
        onIdle: () -> Unit = {},
    ) = PacedInputStream(
        Trickle(),
        intervalMillis = 100,
        onIdle = onIdle,
        nowMillis = { time.nowMillis },
        sleep = time::sleep,
    )

    @Test
    fun `the first read does not wait`() {
        val time = FakeTime()
        paced(time).read(ByteArray(10))
        time.sleeps shouldBe emptyList()
    }

    @Test
    fun `a read sooner than the interval waits out the rest of it`() {
        val time = FakeTime()
        val stream = paced(time)
        stream.read(ByteArray(10))
        time.nowMillis += 30
        stream.read(ByteArray(10))
        time.sleeps shouldBe listOf(70L)
    }

    @Test
    fun `a read after the interval does not wait`() {
        val time = FakeTime()
        val stream = paced(time)
        stream.read(ByteArray(10))
        time.nowMillis += 150
        stream.read(ByteArray(10))
        time.sleeps shouldBe emptyList()
    }

    @Test
    fun `onIdle runs before every underlying read, before any wait`() {
        val time = FakeTime()
        val events = mutableListOf<String>()
        val stream = paced(time) { events += "idle@${time.nowMillis}" }
        stream.read(ByteArray(10))
        stream.read(ByteArray(10))
        events shouldBe listOf("idle@1000", "idle@1000")
        time.sleeps shouldBe listOf(100L)
    }

    @Test
    fun `available reports nothing, so the decoder never probes the pipe`() {
        paced(FakeTime()).available() shouldBe 0
    }

    /**
     * Review finding: with an 8 KiB decoder buffer, one read per interval capped capture
     * at ~80 KiB/s. A read that fills the whole request means a backlog, so the next
     * read must not wait.
     */
    @Test
    fun `a read that fills the request does not make the next one wait`() {
        val time = FakeTime()
        val stream =
            PacedInputStream(
                ByteArrayInputStream(ByteArray(1_000)),
                intervalMillis = 100,
                nowMillis = { time.nowMillis },
                sleep = time::sleep,
            )
        repeat(5) { stream.read(ByteArray(100)) shouldBe 100 }
        time.sleeps shouldBe emptyList()
    }
}
