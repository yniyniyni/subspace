// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.FailureReason
import space.getsub.core.model.Retryability
import space.getsub.core.model.retryability

/**
 * Spec §6. Retaining the TUN with nothing servicing it blackholes traffic by
 * construction — no blocking routes, no null-route trickery.
 */
class FailClosedTest {
    @Test
    fun aWantedSessionThatWillRetryHoldsTheTun() {
        shouldRetainTun(failClosed = true, intentWanted = true, retryability = Retryability.Retryable) shouldBe
            true
        shouldRetainTun(failClosed = true, intentWanted = true, retryability = Retryability.RetryableCapped) shouldBe
            true
    }

    /**
     * With the setting off, reconnection still happens — the setting decides
     * only whether traffic runs in the clear while it does.
     */
    @Test
    fun theSettingOffReleasesTheTun() {
        shouldRetainTun(failClosed = false, intentWanted = true, retryability = Retryability.Retryable) shouldBe false
    }

    /**
     * A terminal failure means the user must act. Holding the TUN would leave
     * the device with no connectivity and nothing retrying to restore it — a
     * kill switch with no key.
     */
    @Test
    fun aTerminalFailureReleasesTheTunEvenWhenFailClosed() {
        shouldRetainTun(failClosed = true, intentWanted = true, retryability = Retryability.Terminal) shouldBe false
    }

    /**
     * The decision a `Tun2Socks.start` failure now reaches.
     *
     * `TunnelService.attachTun` used to close and null the replacement TUN in
     * that failure block, so this answer had nothing left to act on and traffic
     * ran in the clear with the kill switch on. It now leaves the fd attached
     * and lets this decide.
     *
     * **What this does and does not prove.** It pins the policy — the reason is
     * retryable, and a wanted fail-closed session retains through it — so a
     * later edit reclassifying `TunnelStartFailed` as terminal, or changing
     * [shouldRetainTun], fails here. It does *not* prove the fd survives:
     * that is `TunnelService`'s own control flow, and a `VpnService` cannot be
     * driven from a JVM test in this project (no Robolectric, no mocking
     * library — §10.7 does not justify adding one). §11's device checklist is
     * what confirms the effect.
     */
    @Test
    fun aTunnelStartFailureIsRetainedWhileTheSessionIsWanted() {
        FailureReason.TunnelStartFailed.retryability() shouldBe Retryability.Retryable
        shouldRetainTun(
            failClosed = true,
            intentWanted = true,
            retryability = FailureReason.TunnelStartFailed.retryability(),
        ) shouldBe true
    }

    /** No intent, no hold: an explicit disconnect must leave no TUN behind (§11). */
    @Test
    fun anUnwantedSessionNeverHoldsTheTun() {
        shouldRetainTun(failClosed = true, intentWanted = false, retryability = Retryability.Retryable) shouldBe false
    }
}
