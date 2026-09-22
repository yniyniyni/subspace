// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.settings

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import space.getsub.core.data.GeoInstallRequest
import space.getsub.core.data.GeoInstallResult
import space.getsub.core.data.InstalledGeoAsset
import space.getsub.core.data.ThemePreference
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.DnsTransport
import space.getsub.core.model.GeoDataKind
import space.getsub.core.model.GeoSourceCatalogue
import space.getsub.core.model.PingMode

/**
 * Covers Task 22's substance: theme is persisted through [SettingsSource]
 * (a stand-in here for the real Room-backed
 * [space.getsub.core.data.SettingsRepository] — see
 * [FakeSettingsSource]'s own KDoc for why the same shared-instance trick
 * proves the same thing a Room round-trip would), and the Xray-core version
 * call's failure path is real, not assumed away (§10.4).
 *
 * `viewModelScope` needs a Main dispatcher to run at all outside Android,
 * hence [UnconfinedTestDispatcher] — same reason
 * [space.getsub.feature.home.HomeViewModelTest] sets one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    /**
     * Backed by a [MutableStateFlow], exactly like the real
     * [space.getsub.core.data.SettingDao]'s query flow — a write
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
    private class FakeSettingsSource(
        initial: ThemePreference = ThemePreference.System,
        initialDnsResolver: DnsResolver = DnsResolver.DEFAULT,
    ) : SettingsSource {
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

        private val _dnsResolver = MutableStateFlow(initialDnsResolver)
        override val dnsResolver: Flow<DnsResolver> = _dnsResolver.asStateFlow()
        private val dnsWriteGates = ArrayDeque<CompletableDeferred<Unit>>()
        private var failNextDnsWrite = false

        val dnsWriteCount: Int get() = dnsWriteRequests.size
        private val dnsWriteRequests = mutableListOf<DnsResolver>()

        override suspend fun setDnsResolver(resolver: DnsResolver) {
            dnsWriteRequests += resolver
            val gate = if (dnsWriteGates.isEmpty()) null else dnsWriteGates.removeFirst()
            gate?.await()
            if (failNextDnsWrite) {
                failNextDnsWrite = false
                check(false) { "controlled DNS write failure" }
            }
            _dnsResolver.value = resolver
        }

        fun pauseNextDnsWrite(gate: CompletableDeferred<Unit>) {
            dnsWriteGates.addLast(gate)
        }

        fun failNextDnsWrite() {
            failNextDnsWrite = true
        }

        fun publishDnsResolver(resolver: DnsResolver) {
            _dnsResolver.value = resolver
        }

        private val _dnsOverriddenByProfile = MutableStateFlow(false)
        override val dnsOverriddenByProfile: Flow<Boolean> = _dnsOverriddenByProfile.asStateFlow()

        private val _pingMode = MutableStateFlow(PingMode.TCP)
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

        private val _geoRefreshOnMetered = MutableStateFlow(false)
        override val geoRefreshOnMetered: Flow<Boolean> = _geoRefreshOnMetered.asStateFlow()

        override suspend fun setGeoRefreshOnMetered(enabled: Boolean) {
            _geoRefreshOnMetered.value = enabled
        }

        private val _selectedGeoSourceIds = MutableStateFlow<Set<String>>(emptySet())
        override val selectedGeoSourceIds: Flow<Set<String>> = _selectedGeoSourceIds.asStateFlow()

        override suspend fun setSelectedGeoSourceIds(ids: Set<String>) {
            _selectedGeoSourceIds.value = ids
        }

        private val _bootAutostart = MutableStateFlow(false)
        override val bootAutostart: Flow<Boolean> = _bootAutostart.asStateFlow()

        override suspend fun setBootAutostart(enabled: Boolean) {
            _bootAutostart.value = enabled
        }

        private val _failClosed = MutableStateFlow(true)
        override val failClosed: Flow<Boolean> = _failClosed.asStateFlow()

        override suspend fun setFailClosed(enabled: Boolean) {
            _failClosed.value = enabled
        }

        private val _batteryPromptShown = MutableStateFlow(false)
        override val batteryPromptShown: Flow<Boolean> = _batteryPromptShown.asStateFlow()

        override suspend fun setBatteryPromptShown(shown: Boolean) {
            _batteryPromptShown.value = shown
        }

        private val _perTagBreakdown = MutableStateFlow(false)
        override val perTagBreakdown: Flow<Boolean> = _perTagBreakdown.asStateFlow()

        override suspend fun setPerTagBreakdown(enabled: Boolean) {
            _perTagBreakdown.value = enabled
        }

        var ignoringBatteryOptimizations: Boolean = false

        override suspend fun isIgnoringBatteryOptimizations(): Boolean = ignoringBatteryOptimizations

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

    /**
     * F2 / ruling R39: a controllable stand-in for [TunnelClient.state][space.getsub.service.TunnelClient.state]
     * — [publish] simulates what the real service would report, the same way
     * [space.getsub.feature.home.HomeViewModelTest]'s own fake connection controls
     * `ConnectionState` for [space.getsub.feature.home.HomeViewModel].
     */
    private class FakeTunnelSessionSource(
        initial: ConnectionState = ConnectionState.Disconnected,
    ) : TunnelSessionSource {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<ConnectionState> = _state.asStateFlow()

        fun publish(next: ConnectionState) {
            _state.value = next
        }
    }

    /**
     * Records every [install] call and returns a canned [GeoInstallResult] per filename (default
     * [defaultResult] otherwise) — no gating logic of any kind, matching the real
     * [BoundGeoAssetSource]: [GeoAssetSource.install]'s KDoc is explicit that "Update now" always
     * runs, so this fake has no cap or metered flag to bypass in the first place.
     */
    private class FakeGeoAssetSource(
        installed: List<InstalledGeoAsset> = emptyList(),
        private val results: Map<String, GeoInstallResult> = emptyMap(),
        private val defaultResult: GeoInstallResult = GeoInstallResult.Installed,
    ) : GeoAssetSource {
        private val _installedAssets = MutableStateFlow(installed)
        override val installedAssets: Flow<List<InstalledGeoAsset>> = _installedAssets.asStateFlow()

        val requests = mutableListOf<GeoInstallRequest>()
        val removed = mutableListOf<String>()

        override suspend fun install(request: GeoInstallRequest): GeoInstallResult {
            requests += request
            return results[request.fileName] ?: defaultResult
        }

        override suspend fun remove(fileName: String) {
            removed += fileName
            _installedAssets.value = _installedAssets.value.filterNot { it.fileName == fileName }
        }
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
    fun `theme survives a viewmodel restart`() =
        runTest {
            val settingsSource = FakeSettingsSource()
            val viewModel =
                SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onThemeChanged(ThemePreference.Light)
            advanceUntilIdle()

            val restarted =
                SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())
            restarted.state.value.theme shouldBe ThemePreference.Light
        }

    @Test
    fun `theme starts at System when nothing was ever set`() =
        runTest {
            val viewModel =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.state.value.theme shouldBe ThemePreference.System
        }

    @Test
    fun `hwid setting survives a viewmodel restart`() =
        runTest {
            val settingsSource = FakeSettingsSource()
            val viewModel =
                SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onHwidEnabledChanged(false)
            advanceUntilIdle()

            val restarted =
                SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

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
                    FakeGeoAssetSource(),
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
                    FakeGeoAssetSource(),
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
                    FakeGeoAssetSource(),
                )
            advanceUntilIdle()

            viewModel.state.value.xrayVersion shouldBe XrayVersionState.Unavailable
        }

    // ── M4.5: latency settings ──────────────────────────────────────────────

    private fun viewModel(source: FakeSettingsSource) =
        SettingsViewModel(source, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

    @Test
    fun `latency defaults are tcp, five seconds, launch testing on`() =
        runTest {
            val state = viewModel(FakeSettingsSource()).state.value

            state.pingMode shouldBe PingMode.TCP
            state.pingTimeoutSeconds shouldBe 5
            state.pingOnLaunch shouldBe true
            // Off by default: a large list measured on cellular is real data.
            state.pingOnLaunchMetered shouldBe false
        }

    @Test
    fun `the ping mode survives a viewmodel restart`() =
        runTest {
            val source = FakeSettingsSource()
            viewModel(source).onPingModeChanged(PingMode.PROXY_HEAD)
            advanceUntilIdle()

            viewModel(source).state.value.pingMode shouldBe PingMode.PROXY_HEAD
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

    // ── M6.5: DNS setting ──────────────────────────────────────────────────

    @Test
    fun `changing DNS transport updates the editable state without persisting`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsTransportChanged(DnsTransport.DOH)

            model.state.value.dnsTransport shouldBe DnsTransport.DOH
            source.dnsResolver.first() shouldBe DnsResolver.DEFAULT
        }

    @Test
    fun `selecting the active DNS transport does not claim a draft or write`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsTransportChanged(DnsTransport.DOU)
            source.publishDnsResolver(DnsResolver(DnsTransport.DOH, domain = "https://dns.example.test/dns-query"))
            advanceUntilIdle()

            assertTrue("Selecting the current DNS transport must not write", source.dnsWriteCount == 0)
            assertTrue(
                "Selecting the current DNS transport must not claim the draft",
                model.state.value.dnsTransport == DnsTransport.DOH,
            )
        }

    @Test
    fun `a persisted resolver update does not discard an active DNS transport draft`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsTransportChanged(DnsTransport.DOH)
            source.publishDnsResolver(DnsResolver(DnsTransport.DOU, ip = "9.9.9.9"))
            advanceUntilIdle()

            model.state.value.dnsTransport shouldBe DnsTransport.DOH
        }

    @Test
    fun `an invalid DoH endpoint updates the draft but leaves the resolver unchanged`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsTransportChanged(DnsTransport.DOH)
            model.onDnsAddressChanged("cloudflare-dns.com")
            advanceUntilIdle()

            assertTrue(
                "The editable DNS address must retain an invalid draft",
                model.state.value.dnsAddress == "cloudflare-dns.com",
            )
            source.dnsResolver.first() shouldBe DnsResolver.DEFAULT
        }

    @Test
    fun `a valid DoU address persists the resolver`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsTransportChanged(DnsTransport.DOU)
            model.onDnsAddressChanged("9.9.9.9")
            advanceUntilIdle()

            source.dnsResolver.first() shouldBe DnsResolver(DnsTransport.DOU, ip = "9.9.9.9")
        }

    @Test
    fun `overlapping DNS writes persist the latest valid resolver`() =
        runTest {
            val firstWrite = CompletableDeferred<Unit>()
            val source = FakeSettingsSource()
            source.pauseNextDnsWrite(firstWrite)
            val model = viewModel(source)

            model.onDnsAddressChanged("9.9.9.9")
            advanceUntilIdle()
            model.onDnsAddressChanged("8.8.8.8")
            firstWrite.complete(Unit)
            advanceUntilIdle()

            val latest = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8")
            assertTrue("DNS writes must be serialized", source.dnsWriteCount == 2)
            val persisted = source.dnsResolver.first()
            assertTrue("The latest DNS resolver must win", persisted == latest)
            assertTrue(
                "Successful DNS writes must normalize visible state",
                model.state.value.dnsAddress == latest.ip,
            )
        }

    @Test
    fun `a failed latest DNS write releases the draft to the persisted resolver`() =
        runTest {
            val source = FakeSettingsSource()
            source.failNextDnsWrite()
            val model = viewModel(source)

            model.onDnsAddressChanged("9.9.9.9")
            advanceUntilIdle()

            assertTrue(
                "A failed DNS write must restore the persisted projection",
                model.state.value.dnsAddress == DnsResolver.DEFAULT.ip,
            )
            model.onDnsAddressChanged("8.8.8.8")
            advanceUntilIdle()

            val persisted = source.dnsResolver.first()
            assertTrue(
                "A failed DNS write must release ownership for the next edit",
                persisted == DnsResolver(DnsTransport.DOU, ip = "8.8.8.8"),
            )
        }

    @Test
    fun `DNS edits trim resolver and bootstrap fields before persistence`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsTransportChanged(DnsTransport.DOH)
            model.onDnsAddressChanged(" https://dns.example.test/dns-query ")
            model.onDnsBootstrapIpChanged(" 2001:db8::1 ")
            advanceUntilIdle()

            val persisted = source.dnsResolver.first()
            assertTrue(
                "DNS persistence must trim resolver fields",
                persisted ==
                    DnsResolver(
                        DnsTransport.DOH,
                        domain = "https://dns.example.test/dns-query",
                        ip = "2001:db8::1",
                    ),
            )
            assertTrue(
                "DNS state must display normalized resolver fields",
                model.state.value.dnsAddress == "https://dns.example.test/dns-query" &&
                    model.state.value.dnsBootstrapIp == "2001:db8::1",
            )
        }

    @Test
    fun `a malformed IPv6 DNS address remains an unpersisted draft`() =
        runTest {
            val source = FakeSettingsSource()
            val model = viewModel(source)

            model.onDnsAddressChanged("1:2:3:4:5:6:7:8:9")
            advanceUntilIdle()

            val persisted = source.dnsResolver.first()
            assertTrue("Malformed IPv6 DNS input must not be persisted", persisted == DnsResolver.DEFAULT)
        }

    @Test
    fun `an invalid DoH bootstrap IP leaves the persisted resolver unchanged`() =
        runTest {
            val persisted =
                DnsResolver(
                    transport = DnsTransport.DOH,
                    domain = "https://dns.example/dns-query",
                    ip = "192.0.2.1",
                )
            val source = FakeSettingsSource(initialDnsResolver = persisted)
            val model = viewModel(source)

            model.onDnsBootstrapIpChanged("not-an-address")
            advanceUntilIdle()

            source.dnsResolver.first() shouldBe persisted
        }

    // ── M5: geo databases (Task 17) ─────────────────────────────────────────

    @Test
    fun `geo rows start at the v2fly defaults, uninstalled`() =
        runTest {
            val state = viewModel(FakeSettingsSource()).state.value

            state.geoRows.map { it.sourceId } shouldBe listOf("v2fly-geoip", "v2fly-geosite")
            state.geoRows.all { it.installedAt == null } shouldBe true
        }

    @Test
    fun `selecting a different source for the same kind replaces the previous selection`() =
        runTest {
            val model = viewModel(FakeSettingsSource())

            model.onGeoSourceSelected("loyalsoldier-geoip")
            advanceUntilIdle()

            // v2fly-geoip and loyalsoldier-geoip both install as geoip.dat: only one can be
            // "selected" at a time, and the domain-kind pick (v2fly-geosite, the untouched
            // default) never enters selectedGeoSourceIds at all — see geoRowsFor's own KDoc for
            // why the default fallback is not persisted as if it were a real user choice.
            model.state.value.selectedGeoSourceIds shouldBe setOf("loyalsoldier-geoip")
        }

    // Branch review, Finding 1: this used to live only in SettingsState's in-memory default,
    // reset to GeoSourceCatalogue.defaults() on every construction, so a deliberate switch away
    // from v2fly was forgotten the moment Settings was reopened.
    @Test
    fun `the geo source selection survives a viewmodel restart`() =
        runTest {
            val source = FakeSettingsSource()
            viewModel(source).onGeoSourceSelected("runetfreedom-geosite")
            advanceUntilIdle()

            val restarted = viewModel(source)
            restarted.state.value.selectedGeoSourceIds shouldBe setOf("runetfreedom-geosite")
            restarted.state.value.geoRows.first { it.installFileName == "geosite.dat" }.sourceId shouldBe
                "runetfreedom-geosite"
        }

    @Test
    fun `update now installs even while the scheduled-refresh metered setting is on`() =
        runTest {
            val settingsSource = FakeSettingsSource()
            settingsSource.setGeoRefreshOnMetered(true)
            val geoAssetSource = FakeGeoAssetSource()
            val model = SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource(), geoAssetSource)
            advanceUntilIdle()
            val row = model.state.value.geoRows.first { it.sourceId == "v2fly-geoip" }

            model.onGeoUpdateNow(row)
            advanceUntilIdle()

            // §A.5: "on" is the constrained state for the *scheduled* refresh this flag gates —
            // a manual update must still go through unconditionally, one install call, no gate.
            geoAssetSource.requests shouldHaveSize 1
            geoAssetSource.requests.first().fileName shouldBe "geoip.dat"
            model.state.value.geoUpdateResults["geoip.dat"] shouldBe GeoInstallResult.Installed
        }

    /**
     * §10.4: every one of [GeoInstallResult]'s four members must reach the UI distinctly — a
     * download failure, a rejected file, and a failed local install are different facts, and
     * collapsing them to a single "update failed" would send a user with a full disk to go check
     * their Wi-Fi.
     */
    @Test
    fun `each of the four install outcomes surfaces as its own distinct result`() =
        runTest {
            GeoInstallResult.entries.forEach { outcome ->
                val geoAssetSource = FakeGeoAssetSource(results = mapOf("geoip.dat" to outcome))
                val model =
                    SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), geoAssetSource)
                val row = model.state.value.geoRows.first { it.installFileName == "geoip.dat" }

                model.onGeoUpdateNow(row)
                advanceUntilIdle()

                model.state.value.geoUpdateResults["geoip.dat"] shouldBe outcome
            }
        }

    @Test
    fun `the geo metered setting survives a viewmodel restart`() =
        runTest {
            val source = FakeSettingsSource()
            viewModel(source).onGeoRefreshOnMeteredChanged(true)
            advanceUntilIdle()

            viewModel(source).state.value.geoRefreshOnMetered shouldBe true
        }

    /**
     * The URL and the install filename are separate fields on [GeoInstallRequest] on purpose
     * (`GeoSource`'s own KDoc): v2fly publishes geosite as `dlc.dat` but xray-core looks for
     * `geosite.dat`, so neither this ViewModel nor anything downstream may derive one from the
     * other. This custom source uses exactly that mismatched shape.
     */
    @Test
    fun `adding a custom source keeps the url and the install filename separate`() =
        runTest {
            val geoAssetSource = FakeGeoAssetSource()
            val model =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), geoAssetSource)

            model.onAddCustomGeoSource(
                url = "https://example.invalid/dlc.dat",
                fileName = "geosite-custom.dat",
                geoType = GeoDataKind.DOMAIN,
            )
            advanceUntilIdle()

            val request = geoAssetSource.requests.single()
            request.sourceUrl shouldBe "https://example.invalid/dlc.dat"
            request.fileName shouldBe "geosite-custom.dat"
            request.geoType shouldBe GeoDataKind.DOMAIN
        }

    // ── Branch review, Finding 3: a typo'd custom source had no way to stop being retried ────

    @Test
    fun `removing a custom source calls through to the repository`() =
        runTest {
            val custom =
                InstalledGeoAsset(
                    fileName = "geosite-custom.dat",
                    sourceUrl = "https://example.invalid/typo.dat",
                    geoType = GeoDataKind.DOMAIN,
                    sizeBytes = null,
                    installedAt = null,
                    lastAttemptedAt = 1_754_000_000_000L,
                    lastFailure = "DownloadFailed",
                )
            val geoAssetSource = FakeGeoAssetSource(installed = listOf(custom))
            val model =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), geoAssetSource)
            advanceUntilIdle()

            model.onRemoveCustomGeoSource("geosite-custom.dat")
            advanceUntilIdle()

            geoAssetSource.removed shouldBe listOf("geosite-custom.dat")
        }

    // A catalogue row always has somewhere to reappear from (the picker, or GeoSourceCatalogue
    // .defaults), so removing one would be confusing at best — this must be refused, not just
    // hidden from the UI (the same "the gate belongs here too" reasoning onRemoveCustomGeoSource's
    // own KDoc documents).
    @Test
    fun `removing a catalogue source is refused`() =
        runTest {
            val installed =
                InstalledGeoAsset(
                    fileName = "geoip.dat",
                    sourceUrl = GeoSourceCatalogue.source("v2fly-geoip")!!.downloadUrl,
                    geoType = GeoDataKind.IP,
                    sizeBytes = 1_000,
                    installedAt = 1_754_000_000_000L,
                    lastAttemptedAt = 1_754_000_000_000L,
                    lastFailure = null,
                )
            val geoAssetSource = FakeGeoAssetSource(installed = listOf(installed))
            val model =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), geoAssetSource)
            advanceUntilIdle()

            model.onRemoveCustomGeoSource("geoip.dat")
            advanceUntilIdle()

            geoAssetSource.removed shouldBe emptyList()
        }

    // ── The battery prompt's trigger (spec §7.2) ────────────────────────────
    //
    // `survivalSettingJustEnabled` used to be passed as a literal `true`, with each caller
    // guarding itself with `if (enabled)`. The parameter could not be false in production, so
    // BatteryPromptTest's `doesNotPromptOutOfNowhere` was pinning a value no call site could
    // produce. These cover the call sites instead of the pure function.

    @Test
    fun `turning a survival setting on prompts about battery`() =
        runTest {
            val viewModel =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onBootAutostartChanged(true)
            advanceUntilIdle()

            viewModel.state.value.showBatteryPrompt shouldBe true
        }

    @Test
    fun `turning a survival setting off does not prompt`() =
        runTest {
            val viewModel =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onFailClosedChanged(false)
            advanceUntilIdle()

            viewModel.state.value.showBatteryPrompt shouldBe false
        }

    /**
     * §7.2's third trigger, which raised no prompt at all. Always-on is a deep link and never a
     * switch (§7.1), so the tap is the strongest statement of intent the app can observe.
     */
    @Test
    fun `opening always-on prompts about battery`() =
        runTest {
            val viewModel =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onAlwaysOnOpened()
            advanceUntilIdle()

            viewModel.state.value.showBatteryPrompt shouldBe true
        }

    /** An app already exempt from Doze has nothing to ask for. */
    @Test
    fun `an already exempt app is not prompted`() =
        runTest {
            val settingsSource = FakeSettingsSource().apply { ignoringBatteryOptimizations = true }
            val viewModel =
                SettingsViewModel(settingsSource, FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onAlwaysOnOpened()
            advanceUntilIdle()

            viewModel.state.value.showBatteryPrompt shouldBe false
        }

    // ── M8.5: the per-tag breakdown toggle (Task 15) ────────────────────────

    @Test
    fun `the breakdown setting survives a viewmodel restart`() =
        runTest {
            val source = FakeSettingsSource()
            viewModel(source).onPerTagBreakdownChanged(true)
            advanceUntilIdle()

            viewModel(source).state.value.perTagBreakdown shouldBe true
        }

    /** Not one of §7.2's three survival settings, so it must never raise the Doze prompt. */
    @Test
    fun `turning on the breakdown does not prompt about battery`() =
        runTest {
            val viewModel =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onPerTagBreakdownChanged(true)
            advanceUntilIdle()

            viewModel.state.value.showBatteryPrompt shouldBe false
        }

    // ── I-2 / ruling R43 (revises F2 / ruling R39): session-scoped notice ───
    //
    // TunnelService.startCore reads perTagBreakdown once, at connect, and is deliberately not
    // restarted just to apply a change (ARCHITECTURE.md §10.4).
    // SettingsState.perTagBreakdownSessionNoticeVisible tells Settings whether to say so, and is
    // now derived purely from sessionConnected -- not from a latch of what the user did -- so
    // these cover the two cases review finding I-2 showed the old one-way latch got wrong (a
    // second toggle within one session, and a ViewModel recreated by a screen navigation) plus
    // the ordinary connect/disconnect transitions the old F2 tests already covered.

    private fun connectedSession() = FakeTunnelSessionSource(initial = ConnectionState.Connected(0L, 1080, 0))

    @Test
    fun `the session notice is visible while connected, before any toggle`() =
        runTest {
            // Unlike the removed latch -- which only ever became true from inside
            // onPerTagBreakdownChanged -- this is a fact about the running session, not a
            // reaction to an edit: visible even when the switch has never been touched.
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(),
                    FakeAppVersionSource(),
                    FakeGeoAssetSource(),
                    connectedSession(),
                )
            advanceUntilIdle()

            viewModel.state.value.sessionConnected shouldBe true
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe true
        }

    @Test
    fun `the session notice is not visible while disconnected`() =
        runTest {
            // Constructor default (DisconnectedTunnelSessionSource): no session running, and the
            // very next connect reads the live value directly -- nothing to say the notice about.
            val viewModel =
                SettingsViewModel(FakeSettingsSource(), FakeXraySource(), FakeAppVersionSource(), FakeGeoAssetSource())

            viewModel.onPerTagBreakdownChanged(false)
            advanceUntilIdle()

            viewModel.state.value.sessionConnected shouldBe false
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe false
        }

    /**
     * Review finding I-2, case (a): a session that started with the breakdown **on**, toggled
     * off and then back on. Under the removed latch this re-armed on the second toggle and the
     * screen picked the "on" string ("won't start collecting until you reconnect") for a session
     * that had been collecting the whole time -- wrong-direction on the security-relevant half.
     * The notice here carries no direction, so there is nothing for a second toggle to get wrong:
     * it stays visible throughout, and [perTagBreakdown] simply tracks the switch.
     */
    @Test
    fun `toggling the breakdown off then back on in one connected session never states a false direction`() =
        runTest {
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(),
                    FakeAppVersionSource(),
                    FakeGeoAssetSource(),
                    connectedSession(),
                )
            advanceUntilIdle()
            viewModel.state.value.perTagBreakdown shouldBe false // FakeSettingsSource's own default

            viewModel.onPerTagBreakdownChanged(true)
            advanceUntilIdle()
            viewModel.state.value.perTagBreakdown shouldBe true
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe true

            // The toggle back -- the case the old latch got wrong.
            viewModel.onPerTagBreakdownChanged(false)
            advanceUntilIdle()
            viewModel.state.value.perTagBreakdown shouldBe false
            // Still visible, still the one direction-independent string -- true whichever way
            // perTagBreakdown just moved, so this assertion alone rules out a stale "on" reading.
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe true
            viewModel.state.value.sessionConnected shouldBe true
        }

    /**
     * Review finding I-2, case (b): navigating away from Settings and back recreates the
     * `ViewModel` (`SettingsScreen.kt`'s `hiltViewModel()` is scoped to the nav entry). Under the
     * removed latch the fresh instance's `perTagBreakdownPendingReconnect` defaulted to `false`,
     * silently losing the notice while the running session's exposure had not changed --
     * restoring F2's original finding one navigation later. Modelled here by constructing a
     * second `SettingsViewModel` against the *same* still-connected [FakeTunnelSessionSource],
     * the same "shared fake, fresh instance" shape `theme survives a viewmodel restart` uses.
     */
    @Test
    fun `the session notice survives a viewmodel recreation while still connected`() =
        runTest {
            val settingsSource = FakeSettingsSource()
            val sessionSource = connectedSession()
            val viewModel =
                SettingsViewModel(
                    settingsSource,
                    FakeXraySource(),
                    FakeAppVersionSource(),
                    FakeGeoAssetSource(),
                    sessionSource,
                )
            advanceUntilIdle()
            viewModel.onPerTagBreakdownChanged(true)
            advanceUntilIdle()
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe true

            // Simulates SettingsScreen navigating away and back: a brand-new ViewModel instance,
            // no memory of the toggle above, resubscribing to the same ongoing session.
            val recreated =
                SettingsViewModel(
                    settingsSource,
                    FakeXraySource(),
                    FakeAppVersionSource(),
                    FakeGeoAssetSource(),
                    sessionSource,
                )
            advanceUntilIdle()

            // Correct immediately, with no further action -- StateFlow hands a fresh subscriber
            // its current value, so this does not depend on any write this ViewModel makes.
            recreated.state.value.sessionConnected shouldBe true
            recreated.state.value.perTagBreakdownSessionNoticeVisible shouldBe true
            recreated.state.value.perTagBreakdown shouldBe true
        }

    @Test
    fun `disconnecting hides the session notice -- nothing running left to describe`() =
        runTest {
            val sessionSource = connectedSession()
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(),
                    FakeAppVersionSource(),
                    FakeGeoAssetSource(),
                    sessionSource,
                )
            advanceUntilIdle()
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe true

            sessionSource.publish(ConnectionState.Disconnected)
            advanceUntilIdle()

            viewModel.state.value.sessionConnected shouldBe false
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe false
        }

    /**
     * A fresh [ConnectionState.Connected] — whether from an ordinary reconnect or a
     * retained-TUN restart, which also re-reads the setting via `resolveAndStartCore` — is a
     * session again, so the notice is visible again describing *this* session.
     */
    @Test
    fun `a fresh connect shows the session notice again`() =
        runTest {
            val sessionSource = connectedSession()
            val viewModel =
                SettingsViewModel(
                    FakeSettingsSource(),
                    FakeXraySource(),
                    FakeAppVersionSource(),
                    FakeGeoAssetSource(),
                    sessionSource,
                )
            advanceUntilIdle()

            sessionSource.publish(ConnectionState.Disconnected)
            advanceUntilIdle()
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe false

            sessionSource.publish(ConnectionState.Connected(1_000L, 1080, 0))
            advanceUntilIdle()

            viewModel.state.value.sessionConnected shouldBe true
            viewModel.state.value.perTagBreakdownSessionNoticeVisible shouldBe true
        }
}
