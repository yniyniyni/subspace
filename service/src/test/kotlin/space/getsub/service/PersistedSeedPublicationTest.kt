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

/**
 * ARCHITECTURE.md §11 row 7 (Task 20): the guard around publishing a persisted terminal
 * failure once `TunnelService.onCreate`'s off-scope read of Room returns. Pins that it
 * never overwrites a connect or reconcile that ran in the meantime.
 */
class PersistedSeedPublicationTest {
    private val revoked = failure(FailureReason.Revoked, "VPN permission revoked")
    private val disconnected = ConnectionState.Disconnected

    @Test
    fun `publishes the persisted failure when nothing moved since the seed`() {
        persistedSeedPublication(
            current = disconnected,
            seeded = disconnected,
            persisted = revoked,
            generationAtSeed = 0,
            generationNow = 0,
        ) shouldBe revoked
    }

    @Test
    fun `publishes nothing when there is no persisted failure`() {
        persistedSeedPublication(
            current = disconnected,
            seeded = disconnected,
            persisted = null,
            generationAtSeed = 0,
            generationNow = 0,
        ).shouldBeNull()
    }

    @Test
    fun `publishes nothing once the generation has moved`() {
        persistedSeedPublication(
            current = ConnectionState.Connecting(StartupStage.AllocatingPort),
            seeded = disconnected,
            persisted = revoked,
            generationAtSeed = 0,
            generationNow = 1,
        ).shouldBeNull()
    }

    @Test
    fun `publishes nothing once currentState has moved, even if the generation has not`() {
        // onRevoke publishes a second Failed without bumping generation - the
        // state-identity check is what catches this when the counter alone would not.
        persistedSeedPublication(
            current = failure(FailureReason.CoreStartFailed, "x"),
            seeded = disconnected,
            persisted = revoked,
            generationAtSeed = 0,
            generationNow = 0,
        ).shouldBeNull()
    }

    @Test
    fun `publishes the persisted failure again when the seed itself already remembered it and nothing moved`() {
        // Not a defect case, just confirms the guard does not special-case a non-Disconnected seed:
        // an unchanged Failed seed republishing itself is a harmless no-op the caller can dedupe,
        // and this function does not need to suppress it.
        persistedSeedPublication(
            current = revoked,
            seeded = revoked,
            persisted = revoked,
            generationAtSeed = 2,
            generationNow = 2,
        ) shouldBe revoked
    }
}
