// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.service.log.LogRing

class LogRingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `reads back what it appended, in order`() {
        val ring = LogRing(tmp.newFolder())
        ring.append("first")
        ring.append("second")
        assertEquals(listOf("first", "second"), ring.readAll())
    }

    @Test
    fun `rotation drops the oldest lines and keeps the newest`() {
        // 200 bytes per file: two lines of ~100 bytes fill one file.
        val ring = LogRing(tmp.newFolder(), maxBytesPerFile = 200)
        repeat(40) { ring.append("line-$it".padEnd(90, '.')) }

        val lines = ring.readAll()
        assertTrue("ring must not be empty", lines.isNotEmpty())
        // The newest line is always present. This is the property that matters:
        // a ring that drops the newest line is useless for diagnosing a crash.
        assertTrue(lines.last().startsWith("line-39"))
        // The oldest is gone.
        assertTrue(lines.none { it.startsWith("line-0.") })
    }

    @Test
    fun `bounded by two files, never unbounded growth`() {
        val ring = LogRing(tmp.newFolder(), maxBytesPerFile = 200)
        repeat(500) { ring.append("line-$it".padEnd(90, '.')) }
        // Two files of at most 200 bytes each, plus the line that triggered the
        // final rotation. Generous bound; the point is that it is a bound.
        assertTrue("grew to ${ring.totalBytes()}", ring.totalBytes() <= 600)
    }

    @Test
    fun `survives a missing directory`() {
        val ring = LogRing(File(tmp.root, "not-created-yet"))
        ring.append("x")
        assertEquals(listOf("x"), ring.readAll())
    }

    @Test
    fun `clear empties the ring`() {
        val ring = LogRing(tmp.newFolder())
        ring.append("a")
        ring.clear()
        assertEquals(emptyList<String>(), ring.readAll())
    }
}
