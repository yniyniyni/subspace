// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import space.getsub.core.model.ConnectionState

/**
 * What a freshly created `TunnelService` should publish, if anything, once the
 * persisted terminal failure it asked for off the service scope comes back
 * (ARCHITECTURE.md §11 row 7, Task 20).
 *
 * `onCreate` seeds `currentState` **synchronously** from `TerminalStateMemory`'s
 * in-memory [ConnectionState.Failed] (or `Disconnected`), with no `runBlocking`,
 * then launches a coroutine that reads what Room remembers from the *previous*
 * process. By the time that read returns, the service may already be doing
 * something else entirely — a connect accepted from the UI, a boot-time
 * reconcile — and publishing the persisted failure over that would be exactly
 * the lying-UI defect this whole mechanism exists to close (ARCHITECTURE.md §5.5).
 *
 * This function is the guard: it publishes [persisted] only if nothing has
 * moved since the synchronous seed captured [seeded] and [generationAtSeed],
 * and no one currently wants a session. Three independent checks, not one,
 * because any one alone has a gap a real `TunnelService` can hit:
 *
 * - **State identity** (`current == seeded`) catches a transition that does not
 *   bump `generation` — `onRevoke` publishing a second `Failed` over the seeded
 *   one, say.
 * - **Generation** (`generationNow == generationAtSeed`) catches a connect that
 *   *has* bumped `generation` but, at the instant this runs, has not yet
 *   published anything different from [seeded] — `startTunnel` writes
 *   `currentState` to `Connecting` under the same lock it bumps the generation
 *   in, so in practice the two move together, but nothing here should depend on
 *   that remaining true.
 * - **Intent** (`!intentWanted`) catches the case neither of the above can: a
 *   queued `Reconcile(NullIntentStart)` (boot autostart, always-on, or a sticky
 *   restart) has not yet reached its own `currentState`/`generation` read by
 *   the time this guard runs, so `current == seeded` and the generation is
 *   unchanged, and this would otherwise publish the persisted `Failed` first.
 *   `reconcile(wanted = true, actual = Failed)` then answers `Release`
 *   (`ReconcileTest.aNullIntentStartOnAWantedFailureReleasesTheService`), not
 *   `Start` — so boot autostart and crash recovery could silently fail to
 *   connect whenever a terminal failure was persisted.
 *   A wanted intent means any persisted failure is superseded: every terminal
 *   settlement clears intent alongside publishing, so intent being wanted again
 *   means something asked for a new session since that failure was recorded.
 *
 * @param current `currentState`, read under the lock at the moment of
 *   publication.
 * @param seeded what `onCreate`'s synchronous seed set `currentState` to.
 * @param persisted what `TerminalStateMemory.loadPersisted` read back, or null
 *   if nothing was persisted or the stored row was garbage.
 * @param generationAtSeed `generation`, read in the same locked region as
 *   [seeded].
 * @param generationNow `generation`, read again right before publication.
 * @param intentWanted `SettingsRepository.tunnelSessionWantedNow()`, read off
 *   the service lock before this function is called (it is a suspending Room
 *   read). A wanted intent makes this function decline regardless of the other
 *   two checks, per ARCHITECTURE.md §11 row 7.
 * @param alreadyRecorded `TerminalStateMemory.hasRecorded()`, read under the
 *   lock. Once this process has recorded any state, memory is authoritative and
 *   the Room read may be behind this process's own queued write, so it is never
 *   published; `TerminalStateMemory.settleDeclinedSeed` handles the row instead.
 * @return [persisted] if it should be published, or null if there is nothing to
 *   publish, intent is wanted, this process already recorded a state, or the
 *   service has moved on since it was seeded.
 */
// LongParameterList, ComplexCondition: each parameter and each disjunct above is an
// independent guard with its own race it closes - see the KDoc. Collapsing any of
// them loses a case a real TunnelService can hit.
@Suppress("LongParameterList", "ComplexCondition")
internal fun persistedSeedPublication(
    current: ConnectionState,
    seeded: ConnectionState,
    persisted: ConnectionState.Failed?,
    generationAtSeed: Int,
    generationNow: Int,
    intentWanted: Boolean,
    alreadyRecorded: Boolean,
): ConnectionState? =
    if (persisted == null ||
        current != seeded ||
        generationNow != generationAtSeed ||
        intentWanted ||
        alreadyRecorded
    ) {
        null
    } else {
        persisted
    }
