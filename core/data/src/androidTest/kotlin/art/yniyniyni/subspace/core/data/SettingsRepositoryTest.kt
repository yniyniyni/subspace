// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SettingEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Backtick names with spaces are avoided for the same reason ProfileRepositoryTest
// avoids them: runTest {}'s lambda inherits the enclosing method's JVM name, and
// minSdk 26 makes D8 reject spaces in the synthetic class name below DEX 040.
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {
    private lateinit var db: SubspaceDatabase
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java).build()
        repository = SettingsRepository(db.settingDao()) { "test-hwid" }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun latencyDefaultsAreTcpFiveSecondsAndLaunchPingOn() =
        runTest {
            // TCP by default: it is the figure a user can check against another
            // client or a speed test, and it costs no measurable data.
            repository.pingMode.first() shouldBe PingMode.TCP
            repository.pingTimeoutSeconds.first() shouldBe 5
            repository.pingCheckUrl.first() shouldBe "https://www.gstatic.com/generate_204"
            // Ours, on by default — the session-scoped cache is empty at every cold
            // start and this is what refills it, independent of any provider.
            repository.pingOnLaunch.first() shouldBe true
            // Off by default: a large list measured on cellular is real data.
            repository.pingOnLaunchMetered.first() shouldBe false
        }

    @Test
    fun aStoredPingModeRoundTrips() =
        runTest {
            repository.setPingMode(PingMode.TCP)
            repository.pingMode.first() shouldBe PingMode.TCP

            repository.setPingMode(PingMode.PROXY_HEAD)
            repository.pingMode.first() shouldBe PingMode.PROXY_HEAD
        }

    @Test
    fun anUninterpretableStoredModeFallsBackToTheDefault() =
        runTest {
            // A hand-edited or future-version row. `icmp` is specifically the one
            // that must never come back to life — raw sockets need root.
            db.settingDao().put(SettingEntity(key = "ping_mode", value = "icmp"))

            repository.pingMode.first() shouldBe PingMode.TCP
        }

    @Test
    fun aProviderStyleProxyValueResolvesToProxyHead() =
        runTest {
            // The documented alias: `proxy` means GET, which libXray v26.7.11
            // cannot issue, so it resolves to HEAD rather than being rejected.
            db.settingDao().put(SettingEntity(key = "ping_mode", value = "proxy"))

            repository.pingMode.first() shouldBe PingMode.PROXY_HEAD
        }

    @Test
    fun pingTimeoutIsClampedOnWrite() =
        runTest {
            repository.setPingTimeoutSeconds(99)
            repository.pingTimeoutSeconds.first() shouldBe 15

            repository.setPingTimeoutSeconds(0)
            repository.pingTimeoutSeconds.first() shouldBe 1
        }

    @Test
    fun pingTimeoutIsAlsoClampedOnRead() =
        runTest {
            // Clamped on read too, not only on write: this value reaches libXray
            // as seconds, where a large number is a measurement that never returns.
            db.settingDao().put(SettingEntity(key = "ping_timeout_seconds", value = "3600"))

            repository.pingTimeoutSeconds.first() shouldBe 15
        }

    @Test
    fun anUnparseableTimeoutFallsBackToTheDefault() =
        runTest {
            db.settingDao().put(SettingEntity(key = "ping_timeout_seconds", value = "soon"))

            repository.pingTimeoutSeconds.first() shouldBe 5
        }

    @Test
    fun aBlankCheckUrlFallsBackToTheDefaultRatherThanFetchingNothing() =
        runTest {
            db.settingDao().put(SettingEntity(key = "ping_check_url", value = "   "))

            repository.pingCheckUrl.first() shouldBe "https://www.gstatic.com/generate_204"
        }

    @Test
    fun activeRoutingRuleSetIdDefaultsToNull() =
        runTest {
            repository.activeRoutingRuleSetId.first() shouldBe null
        }

    @Test
    fun activeRoutingRuleSetIdRoundTrips() =
        runTest {
            repository.setActiveRoutingRuleSetId(7L)

            repository.activeRoutingRuleSetId.first() shouldBe 7L
        }

    // Routing off is a real state, not an absent one — clearing must read back
    // as null rather than as 0, which would be a rule set id.
    @Test
    fun clearingActiveRoutingRuleSetIdReadsBackAsNull() =
        runTest {
            repository.setActiveRoutingRuleSetId(7L)

            repository.setActiveRoutingRuleSetId(null)

            repository.activeRoutingRuleSetId.first() shouldBe null
        }

    @Test
    fun concurrentConditionalActivationsChooseExactlyOneRuleSet() =
        runTest {
            val attempts =
                (1L..8L).map { id ->
                    async { id to repository.activateRoutingRuleSetIfNone(id) }
                }.awaitAll()

            attempts.count { it.second } shouldBe 1
            repository.activeRoutingRuleSetId.first() shouldBe attempts.single { it.second }.first
        }

    @Test
    fun conditionalClearNeverClearsANewerSelection() =
        runTest {
            repository.setActiveRoutingRuleSetId(7L)
            repository.setActiveRoutingRuleSetId(8L)

            repository.clearActiveRoutingRuleSetIf(7L) shouldBe false
            repository.activeRoutingRuleSetId.first() shouldBe 8L
            repository.clearActiveRoutingRuleSetIf(8L) shouldBe true
            repository.activeRoutingRuleSetId.first() shouldBe null
        }

    @Test
    fun concurrentClearAndNewSelectionAlwaysKeepTheNewSelection() =
        runTest {
            repeat(CONDITIONAL_SETTING_RACE_REPETITIONS) { iteration ->
                val oldId = iteration.toLong() * 2 + 1
                val newId = oldId + 1
                repository.setActiveRoutingRuleSetId(oldId)
                val start = CompletableDeferred<Unit>()

                val staleClear = async {
                    start.await()
                    repository.clearActiveRoutingRuleSetIf(oldId)
                }
                val newSelection = async {
                    start.await()
                    repository.setActiveRoutingRuleSetId(newId)
                }

                start.complete(Unit)
                awaitAll(staleClear, newSelection)

                repository.activeRoutingRuleSetId.first() shouldBe newId
            }
        }

    @Test
    fun launchPingTogglesRoundTrip() =
        runTest {
            repository.setPingOnLaunch(false)
            repository.pingOnLaunch.first() shouldBe false

            repository.setPingOnLaunchMetered(true)
            repository.pingOnLaunchMetered.first() shouldBe true
        }

    // Branch review: this was previously only an in-memory ViewModel default, reset to
    // GeoSourceCatalogue.defaults() on every construction, so a deliberate picker switch was
    // forgotten the moment Settings was reopened.
    @Test
    fun selectedGeoSourceIdsIsEmptyUntilSet() =
        runTest {
            repository.selectedGeoSourceIds.first() shouldBe emptySet()
        }

    @Test
    fun selectedGeoSourceIdsRoundTrips() =
        runTest {
            repository.setSelectedGeoSourceIds(setOf("loyalsoldier-geoip", "v2fly-geosite"))

            repository.selectedGeoSourceIds.first() shouldBe setOf("loyalsoldier-geoip", "v2fly-geosite")
        }

    @Test
    fun perAppDefaultsToOffWithNothingSelected() =
        runTest {
            // Off is the default because it is the only mode that cannot change
            // which traffic leaves the device before the user has chosen anything.
            repository.perAppMode.first() shouldBe PerAppMode.Off
            repository.perAppUserPackages.first() shouldBe emptySet()
        }

    @Test
    fun aStoredPerAppModeRoundTrips() =
        runTest {
            repository.setPerAppMode(PerAppMode.AllowList)
            repository.perAppMode.first() shouldBe PerAppMode.AllowList

            repository.setPerAppMode(PerAppMode.DenyList)
            repository.perAppMode.first() shouldBe PerAppMode.DenyList

            repository.setPerAppMode(PerAppMode.Off)
            repository.perAppMode.first() shouldBe PerAppMode.Off
        }

    @Test
    fun anUninterpretableStoredPerAppModeFallsBackToOff() =
        runTest {
            db.settingDao().put(SettingEntity(key = "per_app_mode", value = "AllowList"))

            repository.perAppMode.first() shouldBe PerAppMode.Off
        }

    @Test
    fun perAppPackagesRoundTripAndClear() =
        runTest {
            repository.setPerAppUserPackages(setOf("com.example.bank", "com.example.maps"))
            repository.perAppUserPackages.first() shouldBe setOf("com.example.bank", "com.example.maps")

            repository.setPerAppUserPackages(emptySet())
            repository.perAppUserPackages.first() shouldBe emptySet()
        }

    // SettingDao exposes no delete, so an empty set is stored as "". Reading that
    // back as a one-element set containing the empty string would put a package
    // named "" into a VpnService.Builder call.
    @Test
    fun anEmptyStoredPackageListIsAnEmptySetNotABlankEntry() =
        runTest {
            db.settingDao().put(SettingEntity(key = "per_app_user_packages", value = ""))

            repository.perAppUserPackages.first() shouldBe emptySet()
        }

    @Test
    fun dnsResolverDefaultsToThePlainCloudflareLiteral() =
        runTest {
            repository.dnsResolver.first() shouldBe DnsResolver(DnsTransport.DOU, ip = "1.1.1.1")
        }

    @Test
    fun dnsResolverRoundTripsADohEndpoint() =
        runTest {
            val doh = DnsResolver(DnsTransport.DOH, domain = "https://dns.example.test/dns-query", ip = "9.9.9.9")

            repository.setDnsResolver(doh)

            repository.dnsResolver.first() shouldBe doh
        }

    @Test
    fun aStoredResolverWithAGarbageTransportFallsBackToTheDefault() =
        runTest {
            db.settingDao().put(SettingEntity(key = "dns_transport", value = "DoQ"))

            repository.dnsResolver.first() shouldBe DnsResolver(DnsTransport.DOU, ip = "1.1.1.1")
        }

    @Test
    fun dnsResolverNeverPublishesATornMultiRowProjection() =
        runTest {
            val expected =
                DnsResolver(
                    DnsTransport.DOH,
                    domain = "https://dns.example.test/dns-query",
                    ip = "2001:db8::1",
                )
            val observed = mutableListOf<DnsResolver>()
            val reachedExpected = CompletableDeferred<Unit>()
            val collection =
                launch {
                    repository.dnsResolver.collect { resolver ->
                        observed += resolver
                        if (resolver == expected) reachedExpected.complete(Unit)
                    }
                }
            advanceUntilIdle()

            repository.setDnsResolver(expected)
            reachedExpected.await()
            collection.cancel()

            assertTrue(
                "DNS resolver flow must publish only complete resolver snapshots",
                observed.all { resolver -> resolver == DnsResolver.DEFAULT || resolver == expected },
            )
        }

    private companion object {
        const val CONDITIONAL_SETTING_RACE_REPETITIONS = 32
    }
}
