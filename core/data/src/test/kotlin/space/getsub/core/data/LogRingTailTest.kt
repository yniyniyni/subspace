// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogRingTailTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = tmp.newFolder("logs")

    @Test
    fun `the first poll is the whole ring, oldest first`() {
        val d = dir()
        File(d, "log.1").writeText("a\nb\n")
        File(d, "log.0").writeText("c\n")
        LogRingTail(d).poll() shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `an absent directory polls empty, not null, the first time`() {
        LogRingTail(File(tmp.root, "never")).poll() shouldBe emptyList()
    }

    @Test
    fun `nothing changed polls null`() {
        val d = dir()
        File(d, "log.0").writeText("a\n")
        val t = LogRingTail(d)
        t.poll()
        t.poll().shouldBeNull()
    }

    @Test
    fun `appended lines are read incrementally`() {
        val d = dir()
        val log0 = File(d, "log.0").apply { writeText("a\n") }
        val t = LogRingTail(d)
        t.poll()
        log0.appendText("b\nc\n")
        t.poll() shouldBe listOf("a", "b", "c")
    }

    // Review Focus #5.
    @Test
    fun `a half-written line waits for its newline`() {
        val d = dir()
        val log0 = File(d, "log.0").apply { writeText("a\n") }
        val t = LogRingTail(d)
        t.poll()
        log0.appendText("partial")
        t.poll().shouldBeNull()
        log0.appendText(" line\n")
        t.poll() shouldBe listOf("a", "partial line")
    }

    // Review Focus #4: rotation, as LogRing does it (log.0 renamed over log.1, fresh log.0).
    @Test
    fun `a rotation is a full re-read with nothing duplicated or lost at the seam`() {
        val d = dir()
        val log0 = File(d, "log.0").apply { writeText("a\nb\n") }
        val log1 = File(d, "log.1")
        val t = LogRingTail(d)
        t.poll()
        log0.renameTo(log1) shouldBe true
        File(d, "log.0").writeText("c\n")
        t.poll() shouldBe listOf("a", "b", "c")
    }

    // Review Focus #4: clear() deletes both files.
    @Test
    fun `a clear empties the view`() {
        val d = dir()
        File(d, "log.1").writeText("a\n")
        File(d, "log.0").writeText("b\n")
        val t = LogRingTail(d)
        t.poll()
        File(d, "log.0").delete()
        File(d, "log.1").delete()
        t.poll() shouldBe emptyList()
    }

    @Test
    fun `utf-8 across a poll boundary is not split`() {
        val d = dir()
        val log0 = File(d, "log.0").apply { writeText("") }
        val t = LogRingTail(d)
        t.poll()
        log0.appendText("привет\n")
        t.poll() shouldBe listOf("привет")
    }
}
