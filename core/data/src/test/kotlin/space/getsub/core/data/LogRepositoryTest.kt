// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}
