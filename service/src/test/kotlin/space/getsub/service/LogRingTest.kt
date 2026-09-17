// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.service.log.LogRing
import java.io.File

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

    @Test
    fun `rotation failure clears current to stay bounded`() {
        val dir = tmp.newFolder()
        // Pre-create log.1 as a non-empty directory to block rotation. This forces
        // renameTo to fail without throwing (it returns false). File.delete() fails on
        // non-empty directories, so it survives the delete() call.
        val log1Dir = File(dir, "log.1")
        log1Dir.mkdirs()
        File(log1Dir, "placeholder").writeText("x")

        val ring = LogRing(dir, maxBytesPerFile = 200)
        // Append enough lines to trigger rotation multiple times. Rotations will fail
        // because log.1 exists as a non-empty directory, exercising the fallback.
        repeat(50) { ring.append("line-$it".padEnd(90, '.')) }

        // Verify totalBytes stays bounded despite rotation failures. This proves the
        // fallback (clearing current when rename fails) prevents unbounded growth.
        val totalBytes = ring.totalBytes()
        assertTrue("grew to $totalBytes", totalBytes <= 600)

        // Clean up the blocking directory so we can verify the newest line was kept.
        log1Dir.listFiles()?.forEach { it.delete() }
        log1Dir.delete()

        // The newest line must still be present, even after many failed rotations.
        // This verifies that clearing current on failed rotation still allows the
        // line being appended to land.
        val lines = ring.readAll()
        assertTrue("ring must not be empty", lines.isNotEmpty())
        assertTrue(lines.last().startsWith("line-49"))
    }
}
