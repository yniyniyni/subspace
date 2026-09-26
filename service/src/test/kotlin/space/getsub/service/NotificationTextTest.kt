// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

/** M8.5 spec §4.4 (amended): the only mapping from state to notification text. */
class NotificationTextTest {
    private fun connected(health: Health) = ConnectionState.Connected(0L, 1080, 0, health)

    @Test
    fun `open and idle share the plain connected text, so browsing does not flicker it`() {
        notificationText(connected(Health.Open)) shouldBe R.string.notification_state_connected
        notificationText(connected(Health.Idle)) shouldBe R.string.notification_state_connected
    }

    @Test
    fun `a stall is said out loud`() {
        notificationText(connected(Health.Stalled)) shouldBe R.string.notification_state_stalled
    }

    @Test
    fun `blocked reads from the published state, not from a field the service samples itself`() {
        notificationText(ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = true)) shouldBe
            R.string.notification_state_reconnecting_blocked
        notificationText(ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = false)) shouldBe
            R.string.notification_state_reconnecting_open
    }

    @Test
    fun `connecting says connecting`() {
        notificationText(ConnectionState.Connecting(StartupStage.StartingCore)) shouldBe
            R.string.notification_connecting
    }

    @Test
    fun `terminal and closing states leave the notification alone`() {
        notificationText(ConnectionState.Disconnecting).shouldBeNull()
        notificationText(ConnectionState.Disconnected).shouldBeNull()
        notificationText(failure(FailureReason.Revoked, "x")).shouldBeNull()
    }

    // Review Focus #3: a publish after stopForeground must not resurrect the notification.
    @Test
    fun `nothing is reposted outside the foreground`() {
        notificationRepost(inForeground = false, postedTextRes = null, next = connected(Health.Stalled)).shouldBeNull()
    }

    @Test
    fun `nothing is reposted for a terminal state even in the foreground`() {
        notificationRepost(true, R.string.notification_state_connected, ConnectionState.Disconnected).shouldBeNull()
        notificationRepost(
            true,
            R.string.notification_state_connected,
            failure(FailureReason.Revoked, "x"),
        ).shouldBeNull()
    }

    @Test
    fun `an unchanged text is not reposted`() {
        notificationRepost(true, R.string.notification_state_connected, connected(Health.Open)).shouldBeNull()
    }

    @Test
    fun `a changed text is reposted`() {
        notificationRepost(true, R.string.notification_state_connected, connected(Health.Stalled)) shouldBe
            R.string.notification_state_stalled
    }
}
