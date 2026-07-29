// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers only what Task 15 added to [HomeViewModel]: routing input through
 * [art.yniyniyni.subspace.core.parser.SubscriptionParser], preserving its
 * typed failure detail alongside the existing string-resource error, and
 * clearing both together on edit. [FailureDetailDisplayTest] covers the
 * feature-local resource mapping used to render that detail.
 *
 * `viewModelScope` needs a Main dispatcher to run at all outside Android, hence
 * [UnconfinedTestDispatcher].
 *
 * These tests **await** rather than read `state.value` straight after the call.
 * §5.3 requires the parse to happen off the main thread, so `parseInput` hops to
 * `Dispatchers.Default` and `onConsentGranted` returns before the result exists.
 * That hop is the fix, not an inconvenience — a version of this test that could
 * still read the result synchronously would be a test asserting the §5.3
 * regression is back. `runTest` bounds the wait in real time, so a parse that
 * never completes fails the test rather than hanging the build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val garbage = "this is not a link, not json, not yaml, and not base64 either!!"

    private class FakeTunnelConnection : TunnelConnection {
        override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Disconnected)

        override fun connect(profile: Profile) = Unit

        override fun disconnect() = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `garbage input populates a generic error and typed detail`() =
        runTest {
            val viewModel = HomeViewModel(FakeTunnelConnection())

            viewModel.onInputChanged(garbage)
            viewModel.onConsentGranted()

            val state = viewModel.state.first { !it.busy && it.inputError != null }
            state.inputError.shouldNotBeNull()
            state.inputErrorDetail shouldBe FailureDetail.Malformed(DetailField.Scheme)
        }

    @Test
    fun `a subsequent keystroke clears both inputError and inputErrorDetail`() =
        runTest {
            val viewModel = HomeViewModel(FakeTunnelConnection())
            viewModel.onInputChanged(garbage)
            viewModel.onConsentGranted()
            // Wait for the failure to actually land before clearing it. Without
            // this the keystroke can overtake the off-thread parse, which would
            // then set the error *after* the clear and make the assertion below
            // fail for a reason that has nothing to do with the behaviour under
            // test.
            viewModel.state.first { !it.busy && it.inputError != null }

            viewModel.onInputChanged("something else entirely")

            val state = viewModel.state.first { it.inputError == null }
            state.inputError.shouldBeNull()
            state.inputErrorDetail.shouldBeNull()
        }
}
