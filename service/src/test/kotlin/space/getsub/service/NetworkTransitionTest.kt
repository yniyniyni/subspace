// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.net.Network
import android.net.NetworkCapabilities
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Spec §5.2. Transitions flap: a Wi-Fi drop, cellular coming up, then Wi-Fi
 * returning is three callbacks in a second, and three tunnel restarts is worse
 * than one.
 *
 * The debouncer's job is to collapse that into one. What it must not do — and
 * did — is collapse it into *none*: a verdict of "not now" used to be
 * indistinguishable from "never", so a network suppressed inside the window was
 * forgotten rather than looked at again. Most assertions below are about which
 * of those two a given sequence produces.
 */
class NetworkTransitionTest {
    @Test
    fun theFirstNetworkAlwaysReconciles() {
        val debouncer = NetworkTransitionDebouncer()

        debouncer.decide(networkId = 1L, nowMillis = 0L) shouldBe TransitionDecision.Reconcile
    }

    /** Capability-only changes re-deliver the same id and must not restart the tunnel. */
    @Test
    fun theSameNetworkReportedAgainIsIgnored() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L)

        debouncer.decide(networkId = 1L, nowMillis = 5_000L) shouldBe TransitionDecision.Ignore
    }

    // ── The debounce window defers; it does not discard ─────────────────────
    //
    // This block replaces a single test that asserted `false` for a different
    // network inside the window. The suppression it pinned is still wanted —
    // collapsing a flap into one reconcile is §5.2's whole purpose, and
    // `aFlapCollapsesToOneReconcile` below still pins exactly that. What was
    // wrong is what the suppression *left behind*: the window guard returned
    // without recording the network and without scheduling anything, so accepted
    // state kept naming a network that was no longer current and nothing ever
    // looked again. Two handoffs inside one window, with connectivity never
    // dropping (so no onLost, so no reset()), could leave the VPN pinned to a
    // dead network — the bug 614abe6 was written to fix, reached by a different
    // route.

    /**
     * A different network inside the window is still collapsed into the acceptance
     * that opened the window — but the verdict now says when to look again, and the
     * caller owes the transition that second look.
     */
    @Test
    fun aDifferentNetworkWithinTheWindowIsDeferredNotDiscarded() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L)

        debouncer.decide(networkId = 2L, nowMillis = 400L) shouldBe TransitionDecision.RecheckAfter(600L)
    }

    /**
     * The point of the deferral: when the window closes, the network that was
     * collapsed is reconciled rather than forgotten.
     *
     * `NetworkMonitor` re-reads `getActiveNetwork()` when its trailing re-check
     * fires rather than replaying the collapsed `Network`, so the second call here
     * is what that re-read resolves to — which in the simple case is still 2.
     */
    @Test
    fun theDeferredNetworkReconcilesOnceTheWindowCloses() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L)
        debouncer.decide(networkId = 2L, nowMillis = 400L) shouldBe TransitionDecision.RecheckAfter(600L)

        debouncer.decide(networkId = 2L, nowMillis = 1_000L) shouldBe TransitionDecision.Reconcile
    }

    /**
     * And in the case the re-read exists for: the network that was collapsed is
     * gone by the time the window closes, and a third one is current. That one
     * reconciles. Replaying the collapsed network here would have re-pinned the
     * tunnel to something that had already stopped being the default.
     */
    @Test
    fun theWindowCloseReconcilesWhateverIsCurrentThenNotWhatWasDeferred() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L)
        debouncer.decide(networkId = 2L, nowMillis = 400L) shouldBe TransitionDecision.RecheckAfter(600L)

        debouncer.decide(networkId = 3L, nowMillis = 1_000L) shouldBe TransitionDecision.Reconcile
    }

    /**
     * Every deferral inside one window names the same instant, which is what makes
     * `NetworkMonitor` re-arming its timer on each in-window delivery safe: the
     * delay is measured from the acceptance that opened the window, so it shrinks
     * monotonically and the target cannot be pushed forward by further chatter.
     */
    @Test
    fun repeatedDeferralsInsideOneWindowAllPointAtTheSameInstant() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L)

        val first = debouncer.decide(networkId = 2L, nowMillis = 200L)
        val second = debouncer.decide(networkId = 2L, nowMillis = 600L)

        first shouldBe TransitionDecision.RecheckAfter(800L)
        second shouldBe TransitionDecision.RecheckAfter(400L)
    }

    @Test
    fun aDifferentNetworkAfterTheWindowReconciles() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L)

        debouncer.decide(networkId = 2L, nowMillis = 1_200L) shouldBe TransitionDecision.Reconcile
    }

    /**
     * The flap that motivates this: Wi-Fi, cellular, Wi-Fi again inside a second
     * must settle as one reconcile, not four. Still one — the deferrals are a
     * standing request to look again when the window closes, not extra reconciles.
     */
    @Test
    fun aFlapCollapsesToOneReconcile() {
        val debouncer = NetworkTransitionDebouncer()

        val decisions =
            listOf(1L to 0L, 2L to 200L, 1L to 400L, 2L to 600L)
                .map { (id, now) -> debouncer.decide(id, now) }

        decisions shouldBe
            listOf(
                TransitionDecision.Reconcile,
                TransitionDecision.RecheckAfter(800L),
                TransitionDecision.Ignore,
                TransitionDecision.RecheckAfter(400L),
            )
        decisions.count { it == TransitionDecision.Reconcile } shouldBe 1
    }

    /**
     * `NetworkMonitor.start()` primes the debouncer with `ConnectivityManager
     * .activeNetwork` before registering, because `registerNetworkCallback` replays
     * `onAvailable` and `onCapabilitiesChanged` immediately for every network that
     * already matches the request — including the one already current. Without
     * priming, that replay reads as a genuine transition and restarts a tunnel that
     * was never actually interrupted — a restart on every connect.
     */
    @Test
    fun primingWithTheAlreadyActiveNetworkSuppressesItsImmediateReplay() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.prime(networkId = 1L)

        debouncer.decide(networkId = 1L, nowMillis = 0L) shouldBe TransitionDecision.Ignore
    }

    /** Priming must not swallow a genuine transition arriving right after registration. */
    @Test
    fun primingDoesNotSuppressAGenuinelyDifferentNetwork() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.prime(networkId = 1L)

        debouncer.decide(networkId = 2L, nowMillis = 0L) shouldBe TransitionDecision.Reconcile
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
     * Without the reset, [NetworkTransitionDebouncer.decide]'s id-equality guard
     * sees the stale id and suppresses the one callback that could resume — and
     * unlike the window guard, that suppression schedules nothing.
     */
    @Test
    fun theSameNetworkReturningAfterALossReconciles() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L) shouldBe TransitionDecision.Reconcile

        debouncer.reset()

        debouncer.decide(networkId = 1L, nowMillis = 100L) shouldBe TransitionDecision.Reconcile
    }

    /**
     * The handover case: Wi-Fi is accepted, lost, and cellular arrives inside the
     * debounce window. Without the reset this is now a deferral rather than a lost
     * wake-up — the trailing re-check would find it — but it is the *only* wake-up
     * a stranded session gets, and there is no working tunnel here whose restart is
     * worth postponing by most of a second. The reset makes it immediate.
     */
    @Test
    fun aHandoverInsideTheWindowAfterALossReconcilesImmediately() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L) shouldBe TransitionDecision.Reconcile

        debouncer.reset()

        // 200ms after the accepted transition — inside the 1s window that would
        // otherwise defer a different network to the end of it.
        debouncer.decide(networkId = 2L, nowMillis = 200L) shouldBe TransitionDecision.Reconcile
    }

    /**
     * The reset must not disable the debouncer for what follows. Once a network is
     * accepted after a loss, the ordinary flap collapsing resumes — otherwise this
     * fix would trade a stranded session for §5.2's three tunnel restarts.
     *
     * One `reset()`, then a burst: this models a single loss followed by a flap.
     */
    @Test
    fun theDebouncerStillCollapsesAFlapAfterALoss() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.reset()

        debouncer.decide(networkId = 1L, nowMillis = 0L) shouldBe TransitionDecision.Reconcile
        debouncer.decide(networkId = 2L, nowMillis = 200L) shouldBe TransitionDecision.RecheckAfter(800L)
        debouncer.decide(networkId = 1L, nowMillis = 400L) shouldBe TransitionDecision.Ignore
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
     * Re-read after the deferral fix, and the trade is unchanged: `reset()` still
     * clears the previous id outright, so these never reach the window guard and
     * never become deferrals. What the fix narrows is the *cost of not resetting*,
     * not the cost of resetting.
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
                debouncer.decide(id, now)
            }

        decisions shouldBe
            listOf(
                TransitionDecision.Reconcile,
                TransitionDecision.Reconcile,
                TransitionDecision.Reconcile,
            )
    }

    // ── The VPN is excluded by the request, not filtered downstream ─────────
    //
    // NetworkMonitor no longer uses registerDefaultNetworkCallback: once this
    // service's own tunnel is up, that API considers the app's default network
    // unchanged when the physical network flips underneath it, so nothing fires
    // — measured on device, the declared underlying stayed pinned to a dead
    // Wi-Fi network across a full Wi-Fi -> LTE -> Wi-Fi cycle. It now listens on
    // a NET_CAPABILITY_NOT_VPN request, so our own tunnel cannot match and the
    // UnderlyingNetworkFilter that used to strip it downstream is gone.
    //
    // Every callback is a trigger only; the value comes from activeNetwork. The
    // debouncer below is the part that is still pure and still worth pinning.

    /**
     * The trigger fires on every matching network, so the same physical network
     * can be re-reported (a capability change, or a sibling network arriving
     * while this one stays current). Re-reporting must not restart the tunnel.
     */
    @Test
    fun reSettlingOnTheSameActiveNetworkDoesNotReconcile() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.prime(108L)

        debouncer.decide(networkId = 108L, nowMillis = 5_000L) shouldBe TransitionDecision.Ignore
        debouncer.decide(networkId = 108L, nowMillis = 9_000L) shouldBe TransitionDecision.Ignore
    }

    /**
     * The transition the old API never delivered: Wi-Fi goes away, cellular is
     * already current, and `activeNetwork` resolves to it. That is a genuine
     * change and must reconcile.
     */
    @Test
    fun aHandoverToTheNetworkUnderneathReconciles() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.prime(108L)

        debouncer.decide(networkId = 100L, nowMillis = 5_000L) shouldBe TransitionDecision.Reconcile
    }

    // ── onCapabilitiesChanged is chatty, and that has to be free ────────────

    /**
     * Observing the default switch means observing `onCapabilitiesChanged`, which
     * also fires for validation, captive-portal, metered and bandwidth-estimate
     * changes on every matching network. `settle()` re-reads `activeNetwork`, so
     * all of that chatter resolves to the id already accepted.
     *
     * The thing that would make the chatter expensive is if an ignored delivery
     * moved the window: it would then be pushed forward on every burst, and a real
     * transition arriving during one would be deferred behind an ever-receding
     * deadline. It does not — the id-equality guard returns before the window guard
     * and writes nothing — so a genuine change 1.1s after the last *acceptance*
     * reconciles at once, no matter how much chatter landed in between.
     */
    @Test
    fun capabilityChatterDoesNotMoveTheWindow() {
        val debouncer = NetworkTransitionDebouncer()
        debouncer.decide(networkId = 1L, nowMillis = 0L) shouldBe TransitionDecision.Reconcile

        val chatter = (1L..9L).map { debouncer.decide(networkId = 1L, nowMillis = it * 100L) }
        chatter.toSet() shouldBe setOf(TransitionDecision.Ignore)

        debouncer.decide(networkId = 2L, nowMillis = 1_100L) shouldBe TransitionDecision.Reconcile
    }

    /**
     * F3 itself, as far as a JVM unit test can reach it: the registered callback
     * overrides `onCapabilitiesChanged`.
     *
     * This is a structural assertion, not a behavioural one, and it is here because
     * the defect was structural — `onAvailable` and `onLost` alone never observe the
     * instant the default network moves, because Android switches the default when
     * Wi-Fi *validates* (a capabilities change on a network that is already
     * available) and the network being handed off from stays up, so neither of those
     * two fires. Nothing else in this module could see that the override was absent;
     * the device row that "passed" was measuring the core redialling on its own.
     *
     * Whether the switch is then handled correctly is a device question (§11), not
     * one this test claims to answer.
     */
    @Test
    fun theCallbackObservesCapabilityChangesAndNotOnlyArrivalAndLoss() {
        val overrides =
            NetworkMonitor.UnderlyingNetworkCallback::class.java.declaredMethods
                .filter { it.name in setOf("onAvailable", "onLost", "onCapabilitiesChanged") }
                .map { it.name }
                .toSet()

        overrides shouldBe setOf("onAvailable", "onLost", "onCapabilitiesChanged")
        NetworkMonitor.UnderlyingNetworkCallback::class.java
            .getDeclaredMethod("onCapabilitiesChanged", Network::class.java, NetworkCapabilities::class.java)
            .declaringClass shouldBe NetworkMonitor.UnderlyingNetworkCallback::class.java
    }
}
