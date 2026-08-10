// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SettingEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.PingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

// Backtick names with spaces are avoided for the same reason ProfileRepositoryTest
// avoids them: runTest {}'s lambda inherits the enclosing method's JVM name, and
// minSdk 26 makes D8 reject spaces in the synthetic class name below DEX 040.
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
    fun latencyDefaultsAreProxyHeadFiveSecondsAndLaunchPingOn() =
        runTest {
            repository.pingMode.first() shouldBe PingMode.PROXY_HEAD
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
    fun anUninterpretableStoredModeFallsBackToProxyHead() =
        runTest {
            // A hand-edited or future-version row. `icmp` is specifically the one
            // that must never come back to life — raw sockets need root.
            db.settingDao().put(SettingEntity(key = "ping_mode", value = "icmp"))

            repository.pingMode.first() shouldBe PingMode.PROXY_HEAD
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
    fun launchPingTogglesRoundTrip() =
        runTest {
            repository.setPingOnLaunch(false)
            repository.pingOnLaunch.first() shouldBe false

            repository.setPingOnLaunchMetered(true)
            repository.pingOnLaunchMetered.first() shouldBe true
        }
}
