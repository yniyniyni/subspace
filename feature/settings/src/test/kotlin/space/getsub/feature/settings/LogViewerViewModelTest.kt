// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import space.getsub.core.data.LogRepository
import space.getsub.feature.settings.log.LogViewerState
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
 * real, thread has not posted back to `Main` yet.
 *
 * Task 10 (M8.5 spec §3.4, as amended) switched [LogViewerViewModel.state]
 * from a one-shot `lines()` load to [LogRepository.tail]. That flow is
 * cold, and [LogViewerViewModel.state]'s `stateIn(..., WhileSubscribed(0))`
 * tears the upstream `tail()` down the instant the subscriber count drops to
 * zero — which `Flow.first(predicate)` does the moment it finds a match,
 * since it cancels its own collection. A first fix-round test suite called
 * `.first { predicate }` several times per test with no other collector
 * running in between: each call subscribed fresh, so each one restarted
 * `tail()` from scratch (a brand-new [space.getsub.core.data.LogRingTail],
 * whose first poll is always an immediate full re-read — never the
 * `delay(intervalMillis)` loop). That meant "state picks up an appended
 * line" never actually exercised a second poll on a live subscription — it
 * passed by re-reading the file fresh, which a one-shot load would also do —
 * and "clear empties the rendered state" raced two independent real-IO
 * paths (`clear()`'s own delete, and the fresh subscription's first poll)
 * with no ordering between them.
 *
 * Every test below now opens exactly **one** [LogViewerViewModel.state]
 * subscription (`resumeUntilEnd`'s `backgroundScope.launch { collect {} }`,
 * kept alive for the rest of the test) before making any assertion. That
 * keeps the subscriber count at or above one for the test's whole body, so
 * `tail()` is never torn down and restarted mid-test: a later assertion
 * genuinely observes the *same* [space.getsub.core.data.LogRingTail] noticing
 * a change on a subsequent real poll, not a fresh subscription's initial
 * re-read. It also gives `clear()`'s delete (real IO, but two tiny files —
 * sub-millisecond) an entire poll interval's head start over the next real
 * poll tick (`LogRepository.TAIL_INTERVAL_MILLIS`, 1 second) to land, instead
 * of racing a brand-new subscription's near-instant first poll.
 *
 * [awaitState] bounds each wait with a real-time [withTimeout] on
 * [Dispatchers.Default], not on this test's own [StandardTestDispatcher].
 * Wrapping the timeout in the *virtual* dispatcher instead would be actively
 * wrong here: kotlinx-coroutines-test's `runTest` auto-advances a virtual
 * scheduler that has nothing else queued straight to the next scheduled
 * timer, and while this coroutine is genuinely waiting on the real
 * `Dispatchers.IO` poll loop to post a result back, the *only* thing
 * scheduled on the virtual scheduler is the timeout's own deadline — so
 * `runTest` would jump straight to it and fail the wait instantly, even
 * though the real work was still legitimately in flight. Running the
 * timeout on a real dispatcher decouples its clock from that scheduler.
 *
 * Every test ends by calling [stopAndJoin]. `tail()`'s poll loop runs
 * through `flowOn(Dispatchers.IO)`, a real thread pool; when
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

    /** Opens the one subscription a test holds for its whole body — see the class KDoc. */
    private fun TestScope.subscribe(vm: LogViewerViewModel) {
        backgroundScope.launch { vm.state.collect { } }
    }

    /** A bounded wait on a real dispatcher's clock — see the class KDoc for why. */
    private suspend fun awaitState(
        vm: LogViewerViewModel,
        predicate: (LogViewerState) -> Boolean,
    ) = withContext(Dispatchers.Default) {
        withTimeout(AWAIT_TIMEOUT_MS) { vm.state.first(predicate) }
    }

    private suspend fun LogViewerViewModel.stopAndJoin() {
        viewModelScope.coroutineContext[Job]?.cancelAndJoin()
    }

    @Test
    fun `loads the ring, live`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("alpha\nbravo\n")

            val vm = viewModel(dir)
            subscribe(vm)
            val loaded = awaitState(vm) { !it.loading }

            assertEquals(listOf("alpha", "bravo"), loaded.lines)
            assertEquals(false, loaded.loading)
            vm.stopAndJoin()
        }

    @Test
    fun `an empty ring is a state, not a crash`() =
        runTest(dispatcher) {
            val vm = viewModel(tmp.newFolder("logs"))
            subscribe(vm)
            val loaded = awaitState(vm) { !it.loading }

            assertEquals(emptyList<String>(), loaded.lines)
            vm.stopAndJoin()
        }

    /**
     * Held subscription: [subscribe] runs before [LogRepository.clear] is even
     * called, so the same [space.getsub.core.data.LogRingTail] that already
     * showed "alpha" is still the one polling when the files disappear. The
     * next real poll tick (up to [space.getsub.core.data.LogRepository.TAIL_INTERVAL_MILLIS]
     * later) sees both files gone and the view empties — see the class KDoc
     * for why this ordering, not the previous round's fresh-resubscription
     * race, is what actually runs here.
     */
    @Test
    fun `clear empties the rendered state`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            File(dir, "log.0").writeText("alpha\n")

            val vm = viewModel(dir)
            subscribe(vm)
            awaitState(vm) { !it.loading }

            vm.clear()
            val cleared = awaitState(vm) { !it.loading && it.lines.isEmpty() }

            assertEquals(emptyList<String>(), cleared.lines)
            vm.stopAndJoin()
        }

    /**
     * Held subscription: proves the *same* live [space.getsub.core.data.LogRingTail]
     * polls again and picks up "bravo" — not a fresh subscription's own
     * immediate full re-read, which a one-shot `lines()` load would satisfy
     * just as well. See the class KDoc.
     */
    @Test
    fun `the same subscription polls again and picks up an appended line`() =
        runTest(dispatcher) {
            val dir = tmp.newFolder("logs")
            val log0 = File(dir, "log.0").apply { writeText("alpha\n") }

            val vm = viewModel(dir)
            subscribe(vm)
            awaitState(vm) { it.lines.isNotEmpty() }

            log0.appendText("bravo\n")
            val updated = awaitState(vm) { it.lines.size > 1 }

            assertEquals(listOf("alpha", "bravo"), updated.lines)
            vm.stopAndJoin()
        }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}
