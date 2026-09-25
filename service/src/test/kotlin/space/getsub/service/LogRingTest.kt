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

/**
 * [LogRing.append] is this class's only production surface (review finding
 * M2 — `readAll`/`clear`/`totalBytes` were test-only accessors with no
 * production caller and were deleted). These tests still need a window into
 * what [LogRing.append] actually wrote, so they read `log.0`/`log.1` back
 * directly — the same two file names [LogRing] itself appends to, and the
 * same ones `:core:data`'s `LogRepository` duplicates for the real viewer
 * (see that class's KDoc).
 */
class LogRingTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Oldest first, mirroring the order the deleted `LogRing.readAll()` returned. */
    private fun readAll(dir: File): List<String> =
        listOf(File(dir, "log.1"), File(dir, "log.0"))
            .filter { it.exists() }
            .flatMap { it.readLines() }

    /**
     * Bytes the ring holds in its two files. It counts regular files only. The
     * rotation-failure test blocks rotation by putting a *directory* at `log.1`, and
     * a directory's `length()` is filesystem-specific: 96 B on macOS APFS, 4096 B on
     * Linux ext4. Counting it measured the fixture, not the ring. That passed locally
     * and failed on CI.
     */
    private fun totalBytes(dir: File): Long =
        listOf(File(dir, "log.0"), File(dir, "log.1")).sumOf { if (it.isFile) it.length() else 0L }

    @Test
    fun `reads back what it appended, in order`() {
        val dir = tmp.newFolder()
        val ring = LogRing(dir)
        ring.append("first")
        ring.append("second")
        assertEquals(listOf("first", "second"), readAll(dir))
    }

    @Test
    fun `rotation drops the oldest lines and keeps the newest`() {
        // 200 bytes per file: two lines of ~100 bytes fill one file.
        val dir = tmp.newFolder()
        val ring = LogRing(dir, maxBytesPerFile = 200)
        repeat(40) { ring.append("line-$it".padEnd(90, '.')) }

        val lines = readAll(dir)
        assertTrue("ring must not be empty", lines.isNotEmpty())
        // The newest line is always present. This is the property that matters:
        // a ring that drops the newest line is useless for diagnosing a crash.
        assertTrue(lines.last().startsWith("line-39"))
        // The oldest is gone.
        assertTrue(lines.none { it.startsWith("line-0.") })
    }

    @Test
    fun `bounded by two files, never unbounded growth`() {
        val dir = tmp.newFolder()
        val ring = LogRing(dir, maxBytesPerFile = 200)
        repeat(500) { ring.append("line-$it".padEnd(90, '.')) }
        // Two files of at most 200 bytes each, plus the line that triggered the
        // final rotation. Generous bound; the point is that it is a bound.
        assertTrue("grew to ${totalBytes(dir)}", totalBytes(dir) <= 600)
    }

    @Test
    fun `survives a missing directory`() {
        val dir = File(tmp.root, "not-created-yet")
        val ring = LogRing(dir)
        ring.append("x")
        assertEquals(listOf("x"), readAll(dir))
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

        // Verify total bytes stays bounded despite rotation failures. This proves the
        // fallback (clearing current when rename fails) prevents unbounded growth.
        val bytes = totalBytes(dir)
        assertTrue("grew to $bytes", bytes <= 600)

        // Clean up the blocking directory so we can verify the newest line was kept.
        log1Dir.listFiles()?.forEach { it.delete() }
        log1Dir.delete()

        // The newest line must still be present, even after many failed rotations.
        // This verifies that clearing current on failed rotation still allows the
        // line being appended to land.
        val lines = readAll(dir)
        assertTrue("ring must not be empty", lines.isNotEmpty())
        assertTrue(lines.last().startsWith("line-49"))
    }
}
