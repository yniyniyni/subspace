// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.home

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health

class ConnectionLabelTest {
    @Test
    fun `open and idle both read connected`() {
        ConnectionState.Connected(0L, 1080, 0, Health.Open).labelRes() shouldBe R.string.state_connected
        ConnectionState.Connected(0L, 1080, 0, Health.Idle).labelRes() shouldBe R.string.state_connected
    }

    @Test
    fun `a stall has its own label`() {
        ConnectionState.Connected(0L, 1080, 0, Health.Stalled).labelRes() shouldBe R.string.home_state_stalled
    }

    @Test
    fun `reconnecting says whether traffic is blocked`() {
        ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = true).labelRes() shouldBe
            R.string.home_state_reconnecting_blocked
        ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = false).labelRes() shouldBe
            R.string.home_state_reconnecting_open
    }
}
