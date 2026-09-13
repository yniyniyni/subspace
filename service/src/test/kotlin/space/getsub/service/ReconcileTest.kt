// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.StartupStage
import space.getsub.core.model.failure

/**
 * Spec §3.2. The state machine is the part of M8 most likely to be wrong and the
 * only part that is testable without a device, so these are invariants rather
 * than examples.
 */
class ReconcileTest {
    private val wanted = SessionIntent(wanted = true, profileRowId = 7L)
    private val unwanted = SessionIntent(wanted = false, profileRowId = 7L)

    @Test
    fun nothingWantedMeansStop() {
        reconcile(unwanted, ConnectionState.Disconnected, ReconcileTrigger.NullIntentStart) shouldBe
            ReconcileAction.Stop
    }

    /**
     * Spec §1.3: a null intent is now answerable, so a sticky restart with
     * nothing wanted stops immediately — the outcome START_NOT_STICKY used to
     * protect, reached by asking instead of by refusing.
     */
    @Test
    fun aWantedSessionStartsOnANullIntent() {
        reconcile(wanted, ConnectionState.Disconnected, ReconcileTrigger.NullIntentStart) shouldBe
            ReconcileAction.Start(7L)
    }

    @Test
    fun aWantedSessionWithNoProfileCannotStart() {
        val orphaned = SessionIntent(wanted = true, profileRowId = null)

        reconcile(orphaned, ConnectionState.Disconnected, ReconcileTrigger.NullIntentStart) shouldBe
            ReconcileAction.Stop
    }

    @Test
    fun anAlreadyConnectedSessionIsLeftAlone() {
        val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080)

        reconcile(wanted, connected, ReconcileTrigger.NullIntentStart) shouldBe ReconcileAction.Nothing
    }

    /** A start already in flight must not be started a second time on top of itself. */
    @Test
    fun aStartInFlightIsNotRestarted() {
        val connecting = ConnectionState.Connecting(StartupStage.StartingCore)

        reconcile(wanted, connecting, ReconcileTrigger.NullIntentStart) shouldBe ReconcileAction.Nothing
        reconcile(wanted, connecting, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Nothing
    }

    /** Spec §5.4: the transition restarts core and tun2socks, retaining the TUN. */
    @Test
    fun aNetworkChangeRestartsAConnectedSession() {
        val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080)

        reconcile(wanted, connected, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Restart(7L)
    }

    /**
     * Spec §2.4: with no network there is no timer and no attempt. The service
     * waits on the callback. A retry loop running in Doze is how §11's six-hour
     * screen-off row fails.
     */
    @Test
    fun losingTheNetworkSchedulesNothing() {
        val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080)

        reconcile(wanted, connected, ReconcileTrigger.NetworkLost) shouldBe ReconcileAction.Nothing
    }

    /**
     * Pins the ordering, not just the outcome. The `NetworkLost` check deliberately
     * precedes the intent check, so an unwanted session that loses its network
     * yields `Nothing` rather than the `Stop` the intent check alone would give.
     * Move that early return below the intent check and only this test fails.
     *
     * Spec §2.4 is why the ordering is that way round: no network means no timer and
     * no attempt, unconditionally. A retry timer running in Doze is how the §11
     * six-hour screen-off case fails.
     */
    @Test
    fun losingTheNetworkSchedulesNothingEvenWhenNothingIsWanted() {
        val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080)

        reconcile(unwanted, connected, ReconcileTrigger.NetworkLost) shouldBe ReconcileAction.Nothing
    }

    @Test
    fun networkReturningStartsAReconnectingSession() {
        val reconnecting = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 1)

        reconcile(wanted, reconnecting, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Start(7L)
    }

    @Test
    fun backoffElapsingRetriesAReconnectingSession() {
        val reconnecting = ConnectionState.Reconnecting(FailureReason.CoreStartFailed, attempt = 3)

        reconcile(wanted, reconnecting, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Start(7L)
    }

    /**
     * A terminal failure is not retried even while intent is still being cleared.
     *
     * `Release` rather than `Nothing`: not retried, and not published over either,
     * but the foreground notification and start token a framework start may have
     * just armed are handed back. See [ReconcileAction.Release].
     */
    @Test
    fun aTerminalFailureIsNotRetried() {
        val rejected = failure(FailureReason.ConfigRejected, "core refused it")

        reconcile(wanted, rejected, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Release
        reconcile(wanted, rejected, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Release
    }

    /**
     * Spec §2.3's cap: TunEstablishFailed retries, but not forever.
     *
     * The at-cap half pins an input production cannot produce, and that is stated here
     * rather than left to be rediscovered: `settleRetryableFailure` asks
     * `nextAttemptExceedsCap` *before* publishing and settles as terminal instead, so every
     * published `Reconnecting` carries `attempt < TUN_ESTABLISH_ATTEMPT_CAP`. This pins the
     * second line of defence — if that conversion is ever removed, `reconcile` still refuses
     * to spin — and is not evidence that the arm is live.
     */
    @Test
    fun tunEstablishFailedStopsRetryingAtTheCap() {
        val underCap = ConnectionState.Reconnecting(FailureReason.TunEstablishFailed, attempt = 2)
        val atCap = ConnectionState.Reconnecting(FailureReason.TunEstablishFailed, attempt = 3)

        reconcile(wanted, underCap, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Start(7L)
        reconcile(wanted, atCap, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Nothing
    }

    // ── A cleared intent must not erase a terminal failure ──────────────────
    //
    // Every terminal settlement clears session intent, so `!wanted` is already
    // true when the next trigger arrives. `Stop` from there publishes
    // `Disconnected` over the `Failed`, and the user loses the reason. Seen on a
    // device in the §11 revocation row: Home read `Disconnected` after another
    // VPN app took the route.

    @Test
    fun anUnwantedRevocationKeepsItsReason() {
        val revoked = failure(FailureReason.Revoked, "redacted")

        reconcile(unwanted, revoked, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Release
    }

    /**
     * Whichever trigger arrives after the clear, the failure survives it — that
     * is, no trigger answers `Stop`, which is the action that would publish
     * `Disconnected` over the reason.
     *
     * `NetworkLost` is the one that answers `Nothing`, because §2.4's no-network
     * rule returns before the intent is even read; the rest release. Both are
     * non-publishing, which is what "survives" means here.
     */
    @Test
    fun anUnwantedFailureSurvivesEveryTrigger() {
        val rejected = failure(FailureReason.ConfigRejected, "redacted")

        ReconcileTrigger.entries.forEach { trigger ->
            val expected =
                if (trigger == ReconcileTrigger.NetworkLost) ReconcileAction.Nothing else ReconcileAction.Release

            reconcile(unwanted, rejected, trigger) shouldBe expected
        }
    }

    /** A wanted intent whose row is gone must not erase one either. */
    @Test
    fun anOrphanedIntentDoesNotEraseAFailure() {
        val orphaned = SessionIntent(wanted = true, profileRowId = null)
        val rejected = failure(FailureReason.ConfigRejected, "redacted")

        reconcile(orphaned, rejected, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Release
    }

    // ── A failure must be released, not merely declined ─────────────────────
    //
    // `Nothing` releases nothing. Every non-ACTION_CONNECT start puts the service
    // back into the foreground and records a fresh start id before the reconcile
    // runs, so answering `Nothing` from a settled-down `Failed` left a foreground
    // service over a dead session with an unresolved start token and nothing that
    // would ever stop it — the service outlives its own `stopSelfResult` while
    // `:main` is bound. `Stop` is not the answer either: it publishes
    // `Disconnected` over the reason. Hence the third action.

    /**
     * The exact strand: a terminal failure settles and clears intent, then a boot,
     * always-on or sticky start arrives at the still-running service.
     *
     * Revert either `Failed` arm to `Nothing` and this is the test that fails.
     */
    @Test
    fun aNullIntentStartOnAnUnwantedFailureReleasesTheService() {
        val rejected = failure(FailureReason.ConfigRejected, "redacted")

        reconcile(unwanted, rejected, ReconcileTrigger.NullIntentStart) shouldBe ReconcileAction.Release
    }

    /** The same start arriving before the intent clear has landed. */
    @Test
    fun aNullIntentStartOnAWantedFailureReleasesTheService() {
        val rejected = failure(FailureReason.ConfigRejected, "redacted")

        reconcile(wanted, rejected, ReconcileTrigger.NullIntentStart) shouldBe ReconcileAction.Release
    }

    /**
     * Releasing is not stopping, and the difference is the whole reason this
     * action exists: `Stop` publishes `Disconnected` over the reason the user
     * needs. Nothing reachable from a `Failed` state may answer it.
     */
    @Test
    fun noTriggerEverStopsAFailure() {
        val revoked = failure(FailureReason.Revoked, "redacted")

        ReconcileTrigger.entries.forEach { trigger ->
            reconcile(wanted, revoked, trigger) shouldNotBe ReconcileAction.Stop
            reconcile(unwanted, revoked, trigger) shouldNotBe ReconcileAction.Stop
        }
    }

    /**
     * The other half, and the reason `Disconnected` is not folded in with
     * `Failed` as "already down": §1.3's sticky restart answers a null intent,
     * and with nothing wanted this `Stop` is what shuts the service down.
     */
    @Test
    fun anUnwantedDisconnectedSessionStillStopsTheService() {
        reconcile(unwanted, ConnectionState.Disconnected, ReconcileTrigger.NullIntentStart) shouldBe
            ReconcileAction.Stop
    }

    /** And a session that is still up is still stopped. */
    @Test
    fun anUnwantedLiveSessionIsStillStopped() {
        val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080)

        reconcile(unwanted, connected, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Stop
    }
}
