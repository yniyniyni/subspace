// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LogRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `reads both ring files oldest first`() =
        runTest {
            val dir = tmp.newFolder("logs")
            File(dir, "log.1").writeText("older-a\nolder-b\n")
            File(dir, "log.0").writeText("newer-a\n")

            val repo = LogRepository(dir)
            assertEquals(listOf("older-a", "older-b", "newer-a"), repo.lines())
        }

    @Test
    fun `an absent directory reads empty rather than throwing`() =
        runTest {
            val repo = LogRepository(File(tmp.root, "never-created"))
            assertEquals(emptyList<String>(), repo.lines())
        }

    /**
     * `:bg`'s `LogRing` keeps `log.0` open in append mode. Deleting the file would
     * leave it writing to an unlinked inode, so every line after a clear would
     * vanish. [LogRepository.clear] empties `log.0` in place instead.
     */
    @Test
    fun `a writer holding log 0 open keeps writing visible lines after a clear`() =
        runTest {
            val dir = tmp.newFolder("logs")
            val log0 = File(dir, "log.0")
            java.io.FileOutputStream(log0, true).use { writer ->
                writer.write("before\n".toByteArray())
                LogRepository(dir).clear()
                writer.write("after\n".toByteArray())
            }
            assertEquals(listOf("after"), LogRepository(dir).lines())
        }

    @Test
    fun `clear removes both files`() =
        runTest {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("a\n")
            File(dir, "log.1").writeText("b\n")

            val repo = LogRepository(dir)
            repo.clear()
            assertEquals(emptyList<String>(), repo.lines())
        }

    @Test
    fun `an unreadable log1 does not discard log0's lines`() =
        runTest {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("newer-a\n")
            // A directory in place of the file makes readLines() throw
            // (FileNotFoundException: "Is a directory") rather than merely
            // returning nothing, which is what a shared runCatching around
            // both reads would need to lose log.0 as collateral damage.
            File(dir, "log.1").mkdir()

            val repo = LogRepository(dir)
            assertEquals(listOf("newer-a"), repo.lines())
        }

    @Test
    fun `tail emits the snapshot, then only changes`() =
        runTest {
            val dir = tmp.newFolder("logs")
            val log0 = File(dir, "log.0").apply { writeText("a\n") }
            val repo = LogRepository(dir)

            val seen = mutableListOf<List<String>>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val job = launch { repo.tail(intervalMillis = 1_000, dispatcher = dispatcher).collect { seen += it } }
            runCurrent()
            seen shouldBe listOf(listOf("a"))

            advanceTimeBy(1_001)
            runCurrent()
            seen.size shouldBe 1

            log0.appendText("b\n")
            advanceTimeBy(1_000)
            runCurrent()
            seen.last() shouldBe listOf("a", "b")
            job.cancel()
        }
}
