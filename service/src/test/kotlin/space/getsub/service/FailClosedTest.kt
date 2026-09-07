// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Retryability

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

    /** No intent, no hold: an explicit disconnect must leave no TUN behind (§11). */
    @Test
    fun anUnwantedSessionNeverHoldsTheTun() {
        shouldRetainTun(failClosed = true, intentWanted = false, retryability = Retryability.Retryable) shouldBe false
    }
}
