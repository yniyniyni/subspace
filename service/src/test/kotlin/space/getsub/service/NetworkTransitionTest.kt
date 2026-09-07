// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Spec §5.2. Transitions flap: a Wi-Fi drop, cellular coming up, then Wi-Fi
 * returning is three callbacks in a second, and three tunnel restarts is worse
 * than one.
 */
class NetworkTransitionTest {
    @Test
    fun theFirstNetworkAlwaysReconciles() {
        val debouncer = NetworkTransitionDebouncer()

        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L) shouldBe true
    }

    /** Capability-only changes re-deliver the same id and must not restart the tunnel. */
    @Test
    fun theSameNetworkReportedAgainIsIgnored() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L)

        debouncer.shouldReconcile(networkId = 1L, nowMillis = 5_000L) shouldBe false
    }

    @Test
    fun aDifferentNetworkWithinTheWindowIsSuppressed() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L)

        debouncer.shouldReconcile(networkId = 2L, nowMillis = 400L) shouldBe false
    }

    @Test
    fun aDifferentNetworkAfterTheWindowReconciles() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L)

        debouncer.shouldReconcile(networkId = 2L, nowMillis = 1_200L) shouldBe true
    }

    /**
     * The flap that motivates this: Wi-Fi, cellular, Wi-Fi again inside a second
     * must settle as one reconcile, not three.
     */
    @Test
    fun aFlapCollapsesToOneReconcile() {
        val debouncer = NetworkTransitionDebouncer()

        val decisions =
            listOf(1L to 0L, 2L to 200L, 1L to 400L, 2L to 600L)
                .map { (id, now) -> debouncer.shouldReconcile(id, now) }

        decisions shouldBe listOf(true, false, false, false)
    }
}
