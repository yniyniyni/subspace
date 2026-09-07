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
     * Spec §1.2: reconnecting after the user or another VPN app took the route
     * would be a fight this app should lose, immediately and loudly.
     */
    @Test
    fun revocationStopsRegardlessOfIntent() {
        reconcile(wanted, failure(FailureReason.Revoked, "revoked"), ReconcileTrigger.Revoked) shouldBe
            ReconcileAction.Stop
    }

    @Test
    fun anExplicitDisconnectStops() {
        val connected = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080)

        reconcile(wanted, connected, ReconcileTrigger.UserDisconnect) shouldBe ReconcileAction.Stop
    }

    /** A terminal failure is not retried even while intent is still being cleared. */
    @Test
    fun aTerminalFailureIsNotRetried() {
        val rejected = failure(FailureReason.ConfigRejected, "core refused it")

        reconcile(wanted, rejected, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Nothing
        reconcile(wanted, rejected, ReconcileTrigger.NetworkChanged) shouldBe ReconcileAction.Nothing
    }

    /** Spec §2.3's cap: TunEstablishFailed retries, but not forever. */
    @Test
    fun tunEstablishFailedStopsRetryingAtTheCap() {
        val underCap = ConnectionState.Reconnecting(FailureReason.TunEstablishFailed, attempt = 2)
        val atCap = ConnectionState.Reconnecting(FailureReason.TunEstablishFailed, attempt = 3)

        reconcile(wanted, underCap, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Start(7L)
        reconcile(wanted, atCap, ReconcileTrigger.BackoffElapsed) shouldBe ReconcileAction.Nothing
    }
}
