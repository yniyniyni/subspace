// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers only what Task 15 added to [HomeViewModel]: routing input through
 * [art.yniyniyni.subspace.core.parser.SubscriptionParser], surfacing its
 * redacted failure detail alongside the existing string-resource error, and
 * clearing both together on edit. Connect/disconnect wiring, the busy flag,
 * and the combine of [TunnelConnection.state] predate this task and stay
 * out of scope here.
 *
 * `viewModelScope` needs a Main dispatcher to run at all outside Android;
 * [UnconfinedTestDispatcher] makes `onConsentGranted`'s launch execute
 * synchronously, since [HomeViewModel.parseInput] has no suspension point.
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
    fun `garbage input populates both inputError and inputErrorDetail`() {
        val viewModel = HomeViewModel(FakeTunnelConnection())

        viewModel.onInputChanged(garbage)
        viewModel.onConsentGranted()

        val state = viewModel.state.value
        state.inputError.shouldNotBeNull()
        state.inputErrorDetail.shouldNotBeNull()
    }

    @Test
    fun `a subsequent keystroke clears both inputError and inputErrorDetail`() {
        val viewModel = HomeViewModel(FakeTunnelConnection())
        viewModel.onInputChanged(garbage)
        viewModel.onConsentGranted()

        viewModel.onInputChanged("something else entirely")

        val state = viewModel.state.value
        state.inputError.shouldBeNull()
        state.inputErrorDetail.shouldBeNull()
    }
}
