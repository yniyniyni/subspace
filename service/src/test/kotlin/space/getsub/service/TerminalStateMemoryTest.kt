// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

/**
 * §11 row 7 / W2. A terminal failure must outlive the `TunnelService` instance that
 * published it, because the instance dies while its process lives on: after another
 * VPN app takes the route, `onRevoke` publishes `Failed(Revoked)` correctly and
 * `onDestroy` preserves it correctly — and the next instance starts with
 * `currentState = Disconnected`, so Home reports the wrong thing. Observed on device
 * 2026-09-16: pid unchanged, `ServiceRecord` replaced.
 */
class TerminalStateMemoryTest {
    private fun revoked() = failure(FailureReason.Revoked, "VPN permission revoked")

    @Test
    fun `starts empty so a first-ever instance reports Disconnected`() {
        TerminalStateMemory().lastTerminal() shouldBe null
    }

    @Test
    fun `remembers a terminal failure across instances`() {
        val memory = TerminalStateMemory()

        memory.record(revoked())

        memory.lastTerminal() shouldBe revoked()
    }

    @Test
    fun `a non-terminal state clears it, so a new session cannot show a stale revoke`() {
        val memory = TerminalStateMemory()
        memory.record(revoked())

        memory.record(ConnectionState.Connecting(StartupStage.StartingTunnel))

        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `Disconnected clears it too - an explicit stop is not a failure to report`() {
        val memory = TerminalStateMemory()
        memory.record(revoked())

        memory.record(ConnectionState.Disconnected)

        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `the newest terminal failure replaces an older one`() {
        val memory = TerminalStateMemory()
        memory.record(revoked())

        val later = failure(FailureReason.CoreStartFailed, "IllegalStateException")
        memory.record(later)

        memory.lastTerminal() shouldBe later
    }

    @Test
    fun `seeding prefers the remembered failure and falls back to Disconnected`() {
        val remembered = TerminalStateMemory().apply { record(revoked()) }
        val empty = TerminalStateMemory()

        remembered.seedState() shouldBe revoked()
        empty.seedState() shouldBe ConnectionState.Disconnected
    }
}
