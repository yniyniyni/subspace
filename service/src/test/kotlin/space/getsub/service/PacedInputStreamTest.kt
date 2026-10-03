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

    private fun paced(
        time: FakeTime,
        onIdle: () -> Unit = {},
    ) = PacedInputStream(
        ByteArrayInputStream(ByteArray(100) { 'x'.code.toByte() }),
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
}
