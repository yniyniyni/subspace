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

    /**
     * `NetworkMonitor.start()` primes the debouncer with `ConnectivityManager
     * .activeNetwork` before registering, because `registerDefaultNetworkCallback`
     * replays `onAvailable` immediately for whatever network is already current.
     * Without priming, that replay reads as a genuine transition and restarts a
     * tunnel that was never actually interrupted — a restart on every connect.
     */
    @Test
    fun primingWithTheAlreadyActiveNetworkSuppressesItsImmediateReplay() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.prime(networkId = 1L)

        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L) shouldBe false
    }

    /** Priming must not swallow a genuine transition arriving right after registration. */
    @Test
    fun primingDoesNotSuppressAGenuinelyDifferentNetwork() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.prime(networkId = 1L)

        debouncer.shouldReconcile(networkId = 2L, nowMillis = 0L) shouldBe true
    }

    // ── Losing the network clears the suppression state ─────────────────────
    //
    // These pin an invariant that only became load-bearing once spec §2.4's
    // no-network-no-timer rule was enforced on both edges. `scheduleBackoffRetry`
    // now refuses to arm a timer while `activeNetwork` is null, so for a
    // `Reconnecting` session with no network the `onAvailable` callback is the
    // ONLY thing that can resume it. A suppressed callback used to cost one
    // wasted wakeup; without the timer behind it, it strands the session for
    // good — and with the kill switch on, that is a device with no connectivity
    // and nothing working to restore it.

    /**
     * A network returning with the id it had before the loss must still reconcile.
     * Without the reset, [NetworkTransitionDebouncer.shouldReconcile]'s id-equality
     * guard sees the stale id and suppresses the one callback that could resume.
     */
    @Test
    fun theSameNetworkReturningAfterALossReconciles() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L) shouldBe true

        debouncer.reset()

        debouncer.shouldReconcile(networkId = 1L, nowMillis = 100L) shouldBe true
    }

    /**
     * The handover case, which needs no id collision to strand: Wi-Fi is accepted,
     * lost, and cellular arrives inside the debounce window. The window guard drops
     * it *without recording it*, so the stale id stays in place — which is what
     * would suppress it again on a later delivery.
     */
    @Test
    fun aHandoverInsideTheWindowAfterALossReconciles() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L) shouldBe true

        debouncer.reset()

        // 200ms after the accepted transition — inside the 1s window that would
        // otherwise suppress a different network.
        debouncer.shouldReconcile(networkId = 2L, nowMillis = 200L) shouldBe true
    }

    /**
     * The reset must not disable the debouncer for what follows. Once a network is
     * accepted after a loss, the ordinary flap collapsing resumes — otherwise this
     * fix would trade a stranded session for §5.2's three tunnel restarts.
     *
     * One `reset()`, then a burst: this models a single loss followed by a flap,
     * which is the sequence `registerDefaultNetworkCallback` actually delivers
     * when the default *switches* (onAvailable for the new network, with no
     * onLost for the old one).
     */
    @Test
    fun theDebouncerStillCollapsesAFlapAfterALoss() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.reset()

        debouncer.shouldReconcile(networkId = 1L, nowMillis = 0L) shouldBe true
        debouncer.shouldReconcile(networkId = 2L, nowMillis = 200L) shouldBe false
        debouncer.shouldReconcile(networkId = 1L, nowMillis = 400L) shouldBe false
    }

    /**
     * The cost this fix knowingly accepts, pinned so it is a decision and not a
     * surprise on device row 1.
     *
     * When each arrival IS bracketed by its own `onLost`, nothing collapses any
     * more — every network is accepted, and each acceptance is a tunnel restart.
     * That is the deliberate trade: §5.2's collapsing exists to avoid needless
     * restarts of a *working* tunnel, and after a loss there is no working tunnel
     * to protect, only a session that may be stranded forever if its one wake-up
     * is suppressed. An extra restart is recoverable; a strand is not.
     *
     * If device row 1 shows this costing real restarts on an ordinary Wi-Fi
     * toggle, the fix is to make the suppression conditional on there being a
     * live tunnel to protect — not to drop the reset and reopen the strand.
     */
    @Test
    fun everyLossBracketedArrivalIsAcceptedAndThatIsTheTrade() {
        val debouncer = NetworkTransitionDebouncer()

        val decisions =
            listOf(1L to 0L, 2L to 200L, 1L to 400L).map { (id, now) ->
                debouncer.reset()
                debouncer.shouldReconcile(id, now)
            }

        decisions shouldBe listOf(true, true, true)
    }
}
