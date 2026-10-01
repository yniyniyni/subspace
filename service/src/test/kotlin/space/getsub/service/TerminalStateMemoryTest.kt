// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Health
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

/**
 * ARCHITECTURE.md §11 row 7 / W2. A terminal failure must outlive the `TunnelService` instance that
 * published it, because the instance dies while its process lives on: after another
 * VPN app takes the route, `onRevoke` publishes `Failed(Revoked)` correctly and
 * `onDestroy` preserves it correctly — and the next instance starts with
 * `currentState = Disconnected`, so Home reports the wrong thing. Observed on device
 * 2026-09-16: pid unchanged, `ServiceRecord` replaced.
 *
 * Task 20 (device row 21, part 2) widens the same requirement across *process* death,
 * not just instance death: [TerminalStateMemory] now writes the remembered fact through
 * to Room so it survives the `:bg` process being killed too. The tests below that use
 * [FakePersistence] pin the write-on-change rule the design calls for; the plain
 * in-memory ones above them are unchanged from the instance-death fix.
 */
class TerminalStateMemoryTest {
    private fun revoked() = failure(FailureReason.Revoked, "VPN permission revoked")

    /** A [TerminalStateMemory] with no persistence seam under test, for the in-memory-only tests. */
    private fun bareMemory(): TerminalStateMemory =
        TerminalStateMemory(NoopPersistence, CoroutineScope(Dispatchers.Unconfined))

    private object NoopPersistence : TerminalFailurePersistence {
        override suspend fun load(): PersistedFailure? = null

        override suspend fun save(failure: PersistedFailure?) = Unit
    }

    /**
     * Records every [save] call, in order, for the write-on-change tests.
     *
     * [Dispatchers.Unconfined] in every test below is deliberate, not a shortcut around
     * timing: [TerminalStateMemory]'s single background consumer resumes synchronously on
     * it, so asserting on [saved] right after a [TerminalStateMemory.record] call needs no
     * polling or `advanceUntilIdle`. The ordering guarantee under test — one consumer,
     * drained strictly in turn — holds for any dispatcher; Unconfined only makes it
     * observable without sleeping.
     */
    private class FakePersistence : TerminalFailurePersistence {
        val saved = mutableListOf<PersistedFailure?>()

        override suspend fun load(): PersistedFailure? = null

        override suspend fun save(failure: PersistedFailure?) {
            saved += failure
        }
    }

    @Test
    fun `starts empty so a first-ever instance reports Disconnected`() {
        bareMemory().lastTerminal() shouldBe null
    }

    @Test
    fun `remembers a terminal failure across instances`() {
        val memory = bareMemory()

        memory.record(revoked())

        memory.lastTerminal() shouldBe revoked()
    }

    @Test
    fun `a non-terminal state clears it, so a new session cannot show a stale revoke`() {
        val memory = bareMemory()
        memory.record(revoked())

        memory.record(ConnectionState.Connecting(StartupStage.StartingTunnel))

        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `Disconnected clears it too - an explicit stop is not a failure to report`() {
        val memory = bareMemory()
        memory.record(revoked())

        memory.record(ConnectionState.Disconnected)

        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `the newest terminal failure replaces an older one`() {
        val memory = bareMemory()
        memory.record(revoked())

        val later = failure(FailureReason.CoreStartFailed, "IllegalStateException")
        memory.record(later)

        memory.lastTerminal() shouldBe later
    }

    @Test
    fun `seeding prefers the remembered failure and falls back to Disconnected`() {
        val remembered = bareMemory().apply { record(revoked()) }
        val empty = bareMemory()

        remembered.seedState() shouldBe revoked()
        empty.seedState() shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `persists on a none to Failed transition`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

        memory.record(revoked())

        persistence.saved shouldBe listOf(PersistedFailure(FailureReason.Revoked, "VPN permission revoked"))
    }

    @Test
    fun `persists a clear on a Failed to none transition`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(revoked())

        memory.record(ConnectionState.Disconnected)

        persistence.saved shouldBe
            listOf(
                PersistedFailure(FailureReason.Revoked, "VPN permission revoked"),
                null,
            )
    }

    @Test
    fun `does not persist on repeated non-terminal publishes`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

        memory.record(ConnectionState.Connecting(StartupStage.AllocatingPort))
        memory.record(ConnectionState.Connecting(StartupStage.StartingCore))
        memory.record(ConnectionState.Connecting(StartupStage.EstablishingTun))
        memory.record(ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080))
        memory.record(ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080, health = Health.Open))
        memory.record(ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080, health = Health.Stalled))

        persistence.saved shouldBe emptyList()
    }

    @Test
    fun `does not persist when the same Failed is re-recorded`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(revoked())

        memory.record(revoked())
        memory.record(failure(FailureReason.Revoked, "VPN permission revoked"))

        persistence.saved shouldBe listOf(PersistedFailure(FailureReason.Revoked, "VPN permission revoked"))
    }

    @Test
    fun `a quick Failed to clear cannot be persisted out of order`() =
        runTest {
            // The exact race the design note warns about: the clear is cheap and could race
            // ahead of a slow Failed write if the two were launched independently rather than
            // drained one at a time by a single consumer.
            val persistence = FakePersistence()
            val writeStarted = CompletableDeferred<Unit>()
            val letWriteFinish = CompletableDeferred<Unit>()
            val slowThenFake =
                object : TerminalFailurePersistence {
                    override suspend fun load(): PersistedFailure? = null

                    override suspend fun save(failure: PersistedFailure?) {
                        if (failure != null) {
                            writeStarted.complete(Unit)
                            letWriteFinish.await()
                        }
                        persistence.saved += failure
                    }
                }
            val memory = TerminalStateMemory(slowThenFake, CoroutineScope(Dispatchers.Unconfined))

            memory.record(revoked())
            writeStarted.await()
            // The clear arrives while the Failed write above is still in flight.
            memory.record(ConnectionState.Disconnected)
            letWriteFinish.complete(Unit)

            persistence.saved shouldBe
                listOf(
                    PersistedFailure(FailureReason.Revoked, "VPN permission revoked"),
                    null,
                )
        }

    @Test
    fun `loadPersisted reconstructs a Failed from the persistence seam`() =
        runTest {
            val persistence =
                object : TerminalFailurePersistence {
                    override suspend fun load(): PersistedFailure? =
                        PersistedFailure(FailureReason.Revoked, "VPN permission revoked")

                    override suspend fun save(failure: PersistedFailure?) = Unit
                }
            val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

            memory.loadPersisted() shouldBe revoked()
        }

    @Test
    fun `loadPersisted returns null when nothing was persisted`() =
        runTest {
            val memory = TerminalStateMemory(NoopPersistence, CoroutineScope(Dispatchers.Unconfined))

            memory.loadPersisted().shouldBeNull()
        }
}
