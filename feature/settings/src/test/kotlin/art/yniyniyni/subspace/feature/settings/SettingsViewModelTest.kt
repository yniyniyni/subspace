// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.settings

import art.yniyniyni.subspace.core.data.ThemePreference
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
        override val theme: Flow<ThemePreference> = _theme.asStateFlow()

        override suspend fun setTheme(preference: ThemePreference) {
            _theme.value = preference
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
}
