// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.core.data.LogRepository
import space.getsub.feature.settings.log.LogViewerViewModel
import java.io.File

/**
 * Task 7 brief's Step 1 wrote these three assertions against
 * `dispatcher.scheduler.advanceUntilIdle()` alone, the way
 * `PerAppViewModelTest` (`:feature:routing`) synchronizes against a fake
 * source. That does not work here: unlike every other `*ViewModelTest` in
 * this codebase, this one exercises the real [LogRepository] rather than a
 * fake, and [LogRepository.lines]/[LogRepository.clear] each wrap their body
 * in a real `withContext(Dispatchers.IO)` — the genuine
 * `kotlinx.coroutines.Dispatchers.IO` thread pool, not this test's
 * [StandardTestDispatcher]. `advanceUntilIdle()` only drains work already
 * queued on *this* scheduler; it cannot wait for a callback a different,
 * real, thread has not posted back to `Main` yet. Run against the brief's
 * literal `advanceUntilIdle()`-then-`assertEquals` shape, "loads the ring on
 * init" failed deterministically (5/5 local runs, `expected:<[alpha,
 * bravo]> but was:<[]>`) because the assertion always ran before the real
 * IO thread finished. Do not "fix" this by re-adding `advanceUntilIdle()`
 * alone or by inserting a `Thread.sleep` — [LogRepository] is Task 6's,
 * reviewed clean, and not to be modified for this task, so the fix belongs
 * here: wait on the actual [LogViewerViewModel.state] emission instead of
 * hoping virtual-time draining happens to outrun a real thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loads the ring on init`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("alpha\nbravo\n")

            val vm = LogViewerViewModel(LogRepository(dir))
            val loaded = vm.state.first { !it.loading }

            assertEquals(listOf("alpha", "bravo"), loaded.lines)
            assertEquals(false, loaded.loading)
        }

    @Test
    fun `an empty ring is a state, not a crash`() =
        runTest(dispatcher) {
            val vm = LogViewerViewModel(LogRepository(tmp.newFolder("logs")))
            val loaded = vm.state.first { !it.loading }

            assertEquals(emptyList<String>(), loaded.lines)
        }

    @Test
    fun `clear empties the rendered state`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("alpha\n")

            val vm = LogViewerViewModel(LogRepository(dir))
            vm.state.first { !it.loading }

            vm.clear()
            val cleared = vm.state.first { !it.loading && it.lines.isEmpty() }

            assertEquals(emptyList<String>(), cleared.lines)
        }
}
