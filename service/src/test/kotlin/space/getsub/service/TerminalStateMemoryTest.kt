// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
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

    // ── Fix round 2: Disconnecting is transitional, not a clear (controller ruling R26) ──

    @Test
    fun `Disconnecting after a Failed leaves the persisted value as Failed, with no clear write`() {
        // The exact device defect: TunnelService.stopTunnel(finalState = Failed) publishes
        // Disconnecting first. If that cleared like every other non-terminal state, a process
        // kill during the rest of teardown would leave only the clear in Room.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(revoked())

        memory.record(ConnectionState.Disconnecting)

        persistence.saved shouldBe listOf(PersistedFailure(FailureReason.Revoked, "VPN permission revoked"))
        memory.lastTerminal() shouldBe revoked()
    }

    @Test
    fun `Failed to Disconnecting to Failed causes zero additional writes`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(revoked())

        memory.record(ConnectionState.Disconnecting)
        memory.record(revoked())

        // Only the first record() wrote anything - Disconnecting touched nothing, so the
        // second revoked() is re-recording the same already-remembered value.
        persistence.saved shouldBe listOf(PersistedFailure(FailureReason.Revoked, "VPN permission revoked"))
        memory.lastTerminal() shouldBe revoked()
    }

    @Test
    fun `Failed to Disconnecting to Disconnected clears exactly once`() {
        // An explicit user disconnect from Failed must still clear - just via the final
        // Disconnected publish rather than the Disconnecting that precedes it.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(revoked())

        memory.record(ConnectionState.Disconnecting)
        memory.record(ConnectionState.Disconnected)

        persistence.saved shouldBe
            listOf(
                PersistedFailure(FailureReason.Revoked, "VPN permission revoked"),
                null,
            )
        memory.lastTerminal() shouldBe null
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

    // ── Fix wave #2, Minor 2: memory is authoritative once record has run ──────

    @Test
    fun `hasRecorded is false until record is called`() {
        bareMemory().hasRecorded() shouldBe false
    }

    @Test
    fun `hasRecorded becomes true after a terminal record call`() {
        val memory = bareMemory()

        memory.record(revoked())

        memory.hasRecorded() shouldBe true
    }

    @Test
    fun `hasRecorded becomes true after a non-terminal record call too`() {
        val memory = bareMemory()

        memory.record(ConnectionState.Connecting(StartupStage.AllocatingPort))

        memory.hasRecorded() shouldBe true
    }

    @Test
    fun `hasRecorded becomes true even for a Disconnecting call, which records nothing else`() {
        // A seed racing Room behind this process's own queued write is still a race
        // worth closing even when the only thing recorded so far is Disconnecting -
        // record() having been called at all is what makes memory authoritative here,
        // not what it changed.
        val memory = bareMemory()

        memory.record(ConnectionState.Disconnecting)

        memory.hasRecorded() shouldBe true
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

    // ── Fix round 1: reconciling a stale persisted row (review Important 1) ────

    @Test
    fun `syncPersisted clears a stale persisted row that a race left remembered as null`() {
        // The exact race from the review: Room holds a Revoked from the previous process.
        // This process's memory starts empty, and a Connecting is recorded - the first
        // genuine publish of the new session - before the async seed read resolves.
        // record() alone cannot see this as a change (null -> null), so without
        // syncPersisted the stale row survives untouched.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(ConnectionState.Connecting(StartupStage.AllocatingPort))
        persistence.saved shouldBe emptyList()

        memory.syncPersisted(
            priorPersisted = revoked(),
            current = ConnectionState.Connecting(StartupStage.AllocatingPort),
        )

        persistence.saved shouldBe listOf(null)
        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `syncPersisted republishes a different real failure that raced in ahead of it`() {
        // If a genuine new failure already landed through record() before the seed
        // coroutine's guard declines, syncPersisted must not clobber it - the final
        // persisted row has to match `current`, whatever it actually is.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        val raceWinner = failure(FailureReason.CoreStartFailed, "redacted")
        memory.record(raceWinner)
        persistence.saved shouldBe listOf(PersistedFailure(FailureReason.CoreStartFailed, "redacted"))

        memory.syncPersisted(priorPersisted = revoked(), current = raceWinner)

        memory.lastTerminal() shouldBe raceWinner
        persistence.saved.last() shouldBe PersistedFailure(FailureReason.CoreStartFailed, "redacted")
    }

    @Test
    fun `syncPersisted clears a stale persisted row when intent was wanted and nothing else moved`() {
        // Fix wave #2, Important 1 (controller ruling R27): persistedSeedPublication
        // declines to publish whenever intent is wanted, even when current == seeded,
        // so seedPersistedFailure falls into the same syncPersisted branch as the
        // moved-on race above. This pins that it clears the stale row here too, not
        // just in the case where something else already changed currentState.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

        memory.syncPersisted(priorPersisted = revoked(), current = ConnectionState.Disconnected)

        persistence.saved shouldBe listOf(null)
        memory.lastTerminal() shouldBe null
    }

    // ── Fix wave #2, Minor 1: syncPersisted with current == Disconnecting ──────

    @Test
    fun `syncPersisted with current Disconnecting remembers the prior fact but writes nothing`() {
        // record(Disconnecting) returns early (the same rule it applies when called
        // directly), so the only effect is remembered catching up to what Room
        // already holds - no write is enqueued because nothing there has changed.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

        memory.syncPersisted(priorPersisted = revoked(), current = ConnectionState.Disconnecting)

        persistence.saved shouldBe emptyList()
        memory.lastTerminal() shouldBe revoked()
    }

    @Test
    fun `a later Disconnected after a Disconnecting sync still clears the prior fact`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.syncPersisted(priorPersisted = revoked(), current = ConnectionState.Disconnecting)

        memory.record(ConnectionState.Disconnected)

        persistence.saved shouldBe listOf(null)
        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `a later different Failed after a Disconnecting sync still writes`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.syncPersisted(priorPersisted = revoked(), current = ConnectionState.Disconnecting)

        val newFailure = failure(FailureReason.CoreStartFailed, "redacted")
        memory.record(newFailure)

        persistence.saved shouldBe listOf(PersistedFailure(FailureReason.CoreStartFailed, "redacted"))
        memory.lastTerminal() shouldBe newFailure
    }

    @Test
    fun `syncPersisted is a no-op when the live fact already matches what was persisted`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

        memory.syncPersisted(priorPersisted = revoked(), current = revoked())

        persistence.saved shouldBe emptyList()
        memory.lastTerminal() shouldBe revoked()
    }

    // ── Final re-review #2, I-1: a declined seed once this process has recorded ──

    @Test
    fun `settleDeclinedSeed clears a stale row once this process has already recorded`() {
        // The re-review's path: Room holds a Revoked from the previous process, the
        // null-intent reconcile wins the race and publishes Connecting (null -> null,
        // no write), then the seed read returns. Skipping the seed entirely here left
        // the Revoked in Room to resurface on the next cold open.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(ConnectionState.Connecting(StartupStage.AllocatingPort))
        persistence.saved shouldBe emptyList()

        memory.settleDeclinedSeed(
            priorPersisted = revoked(),
            current = ConnectionState.Connecting(StartupStage.AllocatingPort),
        )

        persistence.saved shouldBe listOf(null)
        memory.lastTerminal() shouldBe null
    }

    @Test
    fun `settleDeclinedSeed rewrites memory's fact, not a read that is behind this process's own write`() {
        // The seed read can return this process's own earlier write. Memory is the
        // authority: the last write must be the live fact, and memory must not change.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        val newer = failure(FailureReason.CoreStartFailed, "redacted")
        memory.record(revoked())
        memory.record(newer)

        memory.settleDeclinedSeed(priorPersisted = revoked(), current = newer)

        persistence.saved.last() shouldBe PersistedFailure(FailureReason.CoreStartFailed, "redacted")
        memory.lastTerminal() shouldBe newer
    }

    @Test
    fun `settleDeclinedSeed during Disconnecting does not adopt the stale read`() {
        // ...so a later identical Failed still persists.
        // syncPersisted would set remembered to the stale Revoked here; a later real
        // Revoked would then look like no change and never reach Room, behind the
        // clear this call must enqueue.
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))
        memory.record(ConnectionState.Connecting(StartupStage.AllocatingPort))
        memory.record(ConnectionState.Disconnecting)

        memory.settleDeclinedSeed(priorPersisted = revoked(), current = ConnectionState.Disconnecting)
        memory.record(revoked())

        persistence.saved shouldBe
            listOf(null, PersistedFailure(FailureReason.Revoked, "VPN permission revoked"))
        memory.lastTerminal() shouldBe revoked()
    }

    @Test
    fun `settleDeclinedSeed before any record falls back to syncPersisted`() {
        val persistence = FakePersistence()
        val memory = TerminalStateMemory(persistence, CoroutineScope(Dispatchers.Unconfined))

        memory.settleDeclinedSeed(priorPersisted = revoked(), current = ConnectionState.Disconnected)

        persistence.saved shouldBe listOf(null)
        memory.lastTerminal() shouldBe null
    }

    // ── Fix round 1: a failed Room read must not reach the tunnel (review Important 2) ──

    @Test
    fun `loadPersisted returns null and persists nothing when the read throws`() =
        runTest {
            val persistence = FakePersistence()
            val throwing =
                object : TerminalFailurePersistence {
                    override suspend fun load(): PersistedFailure? = error("SQLiteException: disk I/O error")

                    override suspend fun save(failure: PersistedFailure?) {
                        persistence.saved += failure
                    }
                }
            val loggedErrors = mutableListOf<String>()
            val memory =
                TerminalStateMemory(
                    persistence = throwing,
                    scope = CoroutineScope(Dispatchers.Unconfined),
                    logError = { msg -> loggedErrors += msg },
                )

            memory.loadPersisted().shouldBeNull()

            // Nothing published (the null return is what onCreate's guard treats as
            // "nothing to seed") and nothing persisted as a side effect of the failed read.
            persistence.saved shouldBe emptyList()
            // Reported, and only by exception class - ARCHITECTURE.md §5.6 forbids the message,
            // which for a real SQLiteException can quote a bound value back.
            loggedErrors shouldBe listOf("failed to read persisted terminal failure: IllegalStateException")
        }

    @Test
    fun `loadPersisted rethrows CancellationException rather than treating it as a read failure`() =
        runTest {
            val cancelling =
                object : TerminalFailurePersistence {
                    override suspend fun load(): PersistedFailure? = throw CancellationException("scope cancelled")

                    override suspend fun save(failure: PersistedFailure?) = Unit
                }
            val memory = TerminalStateMemory(cancelling, CoroutineScope(Dispatchers.Unconfined))

            shouldThrow<CancellationException> { memory.loadPersisted() }
        }
}
