// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.StartupStage
import io.kotest.matchers.shouldBe
import org.junit.Test

class LaunchPingerTest {
    private fun pinger(
        metered: Boolean = false,
        state: ConnectionState = ConnectionState.Disconnected,
        tester: FakeLatencyTester = FakeLatencyTester(),
    ) = LaunchPinger(tester, isMetered = { metered }, connectionState = { state })

    @Test
    fun `runs once per group per session`() {
        val subject = pinger()

        subject.shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
        // The whole point: once per app start, not once per navigation to the list.
        subject.shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe false
    }

    @Test
    fun `a manual group with no provider still runs`() {
        // The case a directive-driven design would have missed entirely: a
        // hand-added group has no provider to enable it.
        pinger().shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
    }

    @Test
    fun `each group is claimed independently`() {
        val subject = pinger()

        subject.shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
        subject.shouldRun(2L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
    }

    @Test
    fun `the global setting off suppresses it`() {
        pinger().shouldRun(1L, enabledGlobally = false, allowMetered = false, providerValue = null) shouldBe false
    }

    @Test
    fun `a provider can force it on for its own group when the setting is off`() {
        pinger().shouldRun(1L, enabledGlobally = false, allowMetered = false, providerValue = "true") shouldBe true
    }

    @Test
    fun `a provider can force it off for its own group when the setting is on`() {
        pinger().shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = "false") shouldBe false
    }

    @Test
    fun `a provider value of 1 enables, per the boolean rule`() {
        pinger().shouldRun(1L, enabledGlobally = false, allowMetered = false, providerValue = "1") shouldBe true
    }

    @Test
    fun `any other provider value disables, per the boolean rule`() {
        // §A.1: only `true` or `1` enables; anything else — including blank —
        // disables. A provider sending "yes" means off, not on.
        pinger().shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = "yes") shouldBe false
        pinger().shouldRun(2L, enabledGlobally = true, allowMetered = false, providerValue = "") shouldBe false
    }

    @Test
    fun `a metered network suppresses it unless allowed`() {
        pinger(metered = true)
            .shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe false
        pinger(metered = true)
            .shouldRun(1L, enabledGlobally = true, allowMetered = true, providerValue = null) shouldBe true
    }

    @Test
    fun `an in-flight connect suppresses it without burning the once-per-session claim`() {
        val tester = FakeLatencyTester()
        val connecting = pinger(state = ConnectionState.Connecting(StartupStage.StartingCore), tester = tester)

        connecting.shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe false

        // Four concurrent Xray instances competing with the start sequence is
        // §5.3's territory — but "not yet" must not mean "never this session".
        val settled = pinger(tester = tester)
        settled.shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
    }

    @Test
    fun `a teardown in flight also suppresses it`() {
        pinger(state = ConnectionState.Disconnecting)
            .shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe false
    }

    @Test
    fun `a settled connected state does not suppress it`() {
        pinger(state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 10800))
            .shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
    }

    @Test
    fun `a disabled run does not burn the claim either`() {
        val tester = FakeLatencyTester()
        val off = pinger(tester = tester)

        off.shouldRun(1L, enabledGlobally = false, allowMetered = false, providerValue = null) shouldBe false
        // Turning the setting on mid-session must be able to take effect.
        off.shouldRun(1L, enabledGlobally = true, allowMetered = false, providerValue = null) shouldBe true
    }
}
