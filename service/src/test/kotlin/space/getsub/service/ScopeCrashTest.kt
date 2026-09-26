// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

class ScopeCrashTest {
    @Test
    fun `a crash while the tunnel still carries traffic publishes nothing`() {
        scopeCrashPublication(ConnectionState.Connected(0L, 1080), tunnelRunning = true, errorClass = "IOException")
            .shouldBeNull()
    }

    @Test
    fun `a crash never overwrites a terminal failure, so Revoked survives`() {
        scopeCrashPublication(
            failure(FailureReason.Revoked, "x"),
            tunnelRunning = false,
            errorClass = "SQLiteException"
        ).shouldBeNull()
    }

    @Test
    fun `a crash with no tunnel running is still reported`() {
        scopeCrashPublication(
            ConnectionState.Connecting(StartupStage.StartingCore),
            false,
            "IllegalStateException"
        ).shouldBe(
            failure(FailureReason.CoreStartFailed, "IllegalStateException")
        )
    }
}
