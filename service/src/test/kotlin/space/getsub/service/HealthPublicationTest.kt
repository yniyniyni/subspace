// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health

class HealthPublicationTest {
    private val connected = ConnectionState.Connected(5L, 1080, 0, Health.Idle)

    @Test
    fun `a changed health on the current session becomes a publish`() {
        nextHealthState(connected, Health.Stalled, detectorGeneration = 3, currentGeneration = 3) shouldBe
            connected.copy(health = Health.Stalled)
    }

    @Test
    fun `an unchanged health publishes nothing`() {
        nextHealthState(connected, Health.Idle, 3, 3).shouldBeNull()
    }

    // Review Focus #1.
    @Test
    fun `a detector from another generation never marks this session`() {
        nextHealthState(connected, Health.Stalled, detectorGeneration = 2, currentGeneration = 3).shouldBeNull()
    }

    @Test
    fun `health is only ever a property of Connected`() {
        nextHealthState(
            ConnectionState.Reconnecting(FailureReason.CoreStartFailed, 1, blocked = true),
            Health.Stalled,
            3,
            3,
        ).shouldBeNull()
        nextHealthState(ConnectionState.Disconnecting, Health.Open, 3, 3).shouldBeNull()
    }
}
