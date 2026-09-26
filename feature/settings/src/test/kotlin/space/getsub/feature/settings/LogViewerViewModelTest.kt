// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
 * fake, and [LogRepository.tail] drives [space.getsub.core.data.LogRingTail]
 * from a real `Dispatchers.IO` thread pool, not this test's
 * [StandardTestDispatcher] — `advanceUntilIdle()` only drains work already
 * queued on *this* scheduler; it cannot wait for a callback a different,
 * real, thread has not posted back to `Main` yet. So every assertion below
 * waits on the actual [LogViewerViewModel.state] emission (`Flow.first`)
 * rather than hoping virtual-time draining happens to outrun a real thread.
 *
 * Task 10 (M8.5 spec §3.4, as amended) switched [LogViewerViewModel.state]
 * from a one-shot `lines()` load to [LogRepository.tail]. The interesting
 * case a one-shot load could not fail is the third test below: the state must
 * pick up an appended line with no explicit refresh call, because there is no
 * `refresh()` left to call.
 *
 * Every test ends by calling [stopAndJoin]. [LogRepository.tail] runs its poll
 * loop through `flowOn(Dispatchers.IO)`, a real thread pool; when
 * [androidx.lifecycle.viewModelScope] is cancelled (or its last subscriber
 * simply goes away), that producer's own completion housekeeping still has to
 * hop back onto `Main` to finish, and it does that hop on whatever thread
 * asks it to run next — which can be a real IO thread, at a time this test
 * does not control. A bare `cancel()` only *requests* that; it returns before
 * the hop happens. If the test method (and this class's `@After`, which
 * resets `Main`) returns first, that hop lands after `Dispatchers.Main` has
 * been reset and crashes looking it up — and since JUnit reuses one JVM
 * across the whole module, the exception surfaces as an unrelated failure in
 * whatever test runs next, not this one. `cancelAndJoin()` waits for the
 * whole `viewModelScope` job tree — including that housekeeping — to
 * actually finish while `Main` is still set, closing the race instead of
 * probabilistically outrunning it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(dir: File): LogViewerViewModel = LogViewerViewModel(LogRepository(dir))

    private suspend fun LogViewerViewModel.stopAndJoin() {
        viewModelScope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test
    fun `loads the ring, live`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("alpha\nbravo\n")

            val vm = viewModel(dir)
            val loaded = vm.state.first { !it.loading }

            assertEquals(listOf("alpha", "bravo"), loaded.lines)
            assertEquals(false, loaded.loading)
            vm.stopAndJoin()
        }

    @Test
    fun `an empty ring is a state, not a crash`() =
        runTest(dispatcher) {
            val vm = viewModel(tmp.newFolder("logs"))
            val loaded = vm.state.first { !it.loading }

            assertEquals(emptyList<String>(), loaded.lines)
            vm.stopAndJoin()
        }

    @Test
    fun `clear empties the rendered state`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("alpha\n")

            val vm = viewModel(dir)
            vm.state.first { !it.loading }

            vm.clear()
            val cleared = vm.state.first { !it.loading && it.lines.isEmpty() }

            assertEquals(emptyList<String>(), cleared.lines)
            vm.stopAndJoin()
        }

    @Test
    fun `state picks up an appended line with no manual refresh`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            val log0 = File(dir, "log.0").apply { writeText("alpha\n") }

            val vm = viewModel(dir)
            vm.state.first { it.lines.isNotEmpty() }

            log0.appendText("bravo\n")
            val updated = vm.state.first { it.lines.size > 1 }

            assertEquals(listOf("alpha", "bravo"), updated.lines)
            vm.stopAndJoin()
        }
}
