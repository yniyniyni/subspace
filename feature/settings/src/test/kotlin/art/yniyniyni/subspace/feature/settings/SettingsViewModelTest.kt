// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.ThemePreference
import art.yniyniyni.subspace.core.model.PingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Covers Task 22's substance: theme is persisted through [SettingsSource]
 * (a stand-in here for the real Room-backed
 * [art.yniyniyni.subspace.core.data.SettingsRepository] — see
 * [FakeSettingsSource]'s own KDoc for why the same shared-instance trick
 * proves the same thing a Room round-trip would), and the Xray-core version
 * call's failure path is real, not assumed away (§10.4).
 *
 * `viewModelScope` needs a Main dispatcher to run at all outside Android,
 * hence [UnconfinedTestDispatcher] — same reason
 * [art.yniyniyni.subspace.feature.home.HomeViewModelTest] sets one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    /**
     * Backed by a [MutableStateFlow], exactly like the real
     * [art.yniyniyni.subspace.core.data.SettingDao]'s query flow — a write
     * through one [SettingsViewModel] instance is visible to a *second*
     * instance constructed against the *same* [FakeSettingsSource]. That is
     * the property under test: a [SettingsViewModel] that instead cached
     * the theme in a local field (the `mutableStateOf` §3 warns against)
     * would still fail this even with a durable fake, because the second
     * instance would just re-read [FakeSettingsSource] correctly regardless
     * — what actually catches a local-field bug is [SettingsViewModel] never
     * being given a way to skip [SettingsSource.theme] in the first place,
     * which is the only source `state.theme` is derived from.
     */
    private class FakeSettingsSource(initial: ThemePreference = ThemePreference.System) : SettingsSource {
        private val _theme = MutableStateFlow(initial)
        private val _hwidEnabled = MutableStateFlow(true)
        override val theme: Flow<ThemePreference> = _theme.asStateFlow()
        override val hwidEnabled: Flow<Boolean> = _hwidEnabled.asStateFlow()
        override val hwid: String = "test-hwid"

        override suspend fun setTheme(preference: ThemePreference) {
            _theme.value = preference
        }

        override suspend fun setHwidEnabled(enabled: Boolean) {
            _hwidEnabled.value = enabled
        }

        private val _pingMode = MutableStateFlow(PingMode.PROXY_HEAD)
        private val _pingCheckUrl = MutableStateFlow("https://www.gstatic.com/generate_204")
        private val _pingTimeoutSeconds = MutableStateFlow(DEFAULT_TIMEOUT)
        private val _pingOnLaunch = MutableStateFlow(true)
        private val _pingOnLaunchMetered = MutableStateFlow(false)

        override val pingMode: Flow<PingMode> = _pingMode.asStateFlow()
        override val pingCheckUrl: Flow<String> = _pingCheckUrl.asStateFlow()
        override val pingTimeoutSeconds: Flow<Int> = _pingTimeoutSeconds.asStateFlow()
        override val pingOnLaunch: Flow<Boolean> = _pingOnLaunch.asStateFlow()
        override val pingOnLaunchMetered: Flow<Boolean> = _pingOnLaunchMetered.asStateFlow()

        override suspend fun setPingMode(mode: PingMode) {
            _pingMode.value = mode
        }

        override suspend fun setPingCheckUrl(url: String) {
            _pingCheckUrl.value = url
        }

        /**
         * Clamps like the real repository does. A fake that stored whatever it
         * was handed would let a test pass on a value that cannot actually reach
         * libXray, where this is seconds.
         */
        override suspend fun setPingTimeoutSeconds(seconds: Int) {
            _pingTimeoutSeconds.value = seconds.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT)
        }

        override suspend fun setPingOnLaunch(enabled: Boolean) {
            _pingOnLaunch.value = enabled
        }

        override suspend fun setPingOnLaunchMetered(enabled: Boolean) {
            _pingOnLaunchMetered.value = enabled
        }

        private companion object {
            const val DEFAULT_TIMEOUT = 5
            const val MIN_TIMEOUT = 1
            const val MAX_TIMEOUT = 15
        }
    }

    private class FakeXraySource(private val result: Result<String> = Result.success("1.8.24")) : XraySource {
        override suspend fun version(): Result<String> = result
    }

    private class FakeAppVersionSource(override val version: String = "0.1.0-alpha01") : AppVersionSource

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `theme survives a viewmodel restart`() =
        runTest {
            val settingsSource = FakeSettingsSource()
            val viewModel = SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource())

            viewModel.onThemeChanged(ThemePreference.Light)
            advanceUntilIdle()

            val restarted = SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource())
            restarted.state.value.theme shouldBe ThemePreference.Light
        }

    @Test
    fun `theme starts at System when nothing was ever set`() =
        runTest {
            val viewModel = SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource())

            viewModel.state.value.theme shouldBe ThemePreference.System
        }

    @Test
    fun `hwid setting survives a viewmodel restart`() =
        runTest {
            val settingsSource = FakeSettingsSource()
            val viewModel = SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource())

            viewModel.onHwidEnabledChanged(false)
            advanceUntilIdle()

            val restarted = SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource())

            restarted.state.value.hwidEnabled shouldBe false
        }

    @Test
    fun `the app version comes from AppVersionSource verbatim`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(),
                    FakeAppVersionSource(version = "9.9.9-test"),
                )

            viewModel.state.value.appVersion shouldBe "9.9.9-test"
        }

    @Test
    fun `a successful xray version call is shown verbatim`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(Result.success("1.8.24")),
                    FakeAppVersionSource(),
                )
            advanceUntilIdle()

            viewModel.state.value.xrayVersion shouldBe XrayVersionState.Available("1.8.24")
        }

    /**
     * §10.4: a real failure path, not an assumed success. A native call that
     * throws must not leave the About row showing a blank string that looks
     * like a real (if empty) answer.
     */
    @Test
    fun `a failed xray version call surfaces as Unavailable, not a blank string`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(Result.failure(RuntimeException("libXray xrayVersion failed"))),
                    FakeAppVersionSource(),
                )
            advanceUntilIdle()

            viewModel.state.value.xrayVersion shouldBe XrayVersionState.Unavailable
        }

    // ── M4.5: latency settings ──────────────────────────────────────────────

    private fun viewModel(source: FakeSettingsSource) =
        SettingsViewModel(source, FakeXraySource(), FakeAppVersionSource())

    @Test
    fun `latency defaults are proxy-head, five seconds, launch testing on`() =
        runTest {
            val state = viewModel(FakeSettingsSource()).state.value

            state.pingMode shouldBe PingMode.PROXY_HEAD
            state.pingTimeoutSeconds shouldBe 5
            state.pingOnLaunch shouldBe true
            // Off by default: a large list measured on cellular is real data.
            state.pingOnLaunchMetered shouldBe false
        }

    @Test
    fun `the ping mode survives a viewmodel restart`() =
        runTest {
            val source = FakeSettingsSource()
            viewModel(source).onPingModeChanged(PingMode.TCP)
            advanceUntilIdle()

            viewModel(source).state.value.pingMode shouldBe PingMode.TCP
        }

    @Test
    fun `the check url round-trips`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onPingCheckUrlChanged("https://example.invalid/204")
            advanceUntilIdle()

            model.state.value.pingCheckUrl shouldBe "https://example.invalid/204"
        }

    @Test
    fun `the timeout cannot be stepped out of range`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            repeat(20) { model.onPingTimeoutChanged(model.state.value.pingTimeoutSeconds + 1) }
            advanceUntilIdle()
            model.state.value.pingTimeoutSeconds shouldBe 15

            repeat(30) { model.onPingTimeoutChanged(model.state.value.pingTimeoutSeconds - 1) }
            advanceUntilIdle()
            model.state.value.pingTimeoutSeconds shouldBe 1
        }

    @Test
    fun `the launch testing switches round-trip`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onPingOnLaunchChanged(false)
            model.onPingOnLaunchMeteredChanged(true)
            advanceUntilIdle()

            model.state.value.pingOnLaunch shouldBe false
            model.state.value.pingOnLaunchMetered shouldBe true
        }
}
