// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock

/**
 * Collapses a flapping transition into one reconcile (spec §5.2).
 *
 * Keyed on the network id, because a capability-only change re-delivers the same
 * network and must not restart a working tunnel.
 */
internal class NetworkTransitionDebouncer(
    private val debounceMillis: Long = 1_000L,
) {
    private var lastNetworkId: Long? = null
    private var lastAtMillis: Long = 0L

    // Each return is a distinct verdict — the same network reported again, a different
    // network arriving inside the debounce window, and a genuine change — and collapsing
    // them into one boolean expression would lose which rule fired.
    @Suppress("ReturnCount")
    fun shouldReconcile(
        networkId: Long,
        nowMillis: Long,
    ): Boolean {
        val previousId = lastNetworkId
        if (previousId == networkId) return false
        if (previousId != null && nowMillis - lastAtMillis < debounceMillis) return false
        lastNetworkId = networkId
        lastAtMillis = nowMillis
        return true
    }

    /**
     * Seeds the last-seen id with [networkId] without counting as a reconcile.
     *
     * `NetworkMonitor.start()` calls this with `ConnectivityManager.activeNetwork`'s
     * id before registering, because `registerDefaultNetworkCallback` replays
     * `onAvailable` immediately for whatever network is already current. Left
     * unprimed, that replay has no previous id to compare against, so
     * [shouldReconcile]'s id-equality guard never applies and the very first
     * callback of every session reads as a genuine transition — tearing down and
     * rebuilding a tunnel that was never actually interrupted.
     *
     * [lastAtMillis] is set far enough in the past that the time-window guard
     * never fires for a network arriving shortly after priming: only the
     * id-equality guard is meant to apply to a primed value, so a genuinely
     * different network right after start() must still reconcile.
     */
    fun prime(networkId: Long) {
        lastNetworkId = networkId
        lastAtMillis = PRIMED_AT_MILLIS
    }

    /**
     * Forgets the last-seen network, so the next one reported is treated as a
     * genuine nothing-to-something transition.
     *
     * Called from `NetworkMonitor`'s `onLost`. Both guards in [shouldReconcile]
     * exist to avoid restarting a *working* tunnel — the same network reported
     * again, and a flap collapsing into one reconcile. Once the default network
     * is gone there is no working tunnel to protect, and whatever arrives next
     * is the thing the session has been waiting for.
     *
     * This became load-bearing when spec §2.4's no-network-no-timer rule started
     * being enforced on both edges: `scheduleBackoffRetry` now refuses to arm a
     * timer while `activeNetwork` is null, which makes this callback the *only*
     * thing that can resume a `Reconnecting` session with no network. A
     * suppressed `onAvailable` used to cost one wasted wakeup, because the timer
     * would retry anyway; without that backstop it strands the session
     * permanently — with the kill switch on, that is a device left with no
     * connectivity and nothing working to restore it.
     *
     * Two reachable ways the suppression fires after a loss, both closed by this:
     * a network returning with the `networkHandle` it had before, and a network
     * arriving within [debounceMillis] of the *previous* acceptance (a Wi-Fi to
     * cellular handover during a flap), which [shouldReconcile] drops without
     * recording, leaving the stale id in place to suppress it again.
     */
    fun reset() {
        lastNetworkId = null
        lastAtMillis = 0L
    }

    private companion object {
        // Long.MIN_VALUE / 2, not Long.MIN_VALUE itself: shouldReconcile computes
        // `nowMillis - lastAtMillis`, and subtracting from the true minimum would
        // overflow for any nowMillis > 0 (wrapping back around to a small or
        // negative result, which would wrongly re-suppress a real transition).
        private const val PRIMED_AT_MILLIS = Long.MIN_VALUE / 2
    }
}

/**
 * Watches the default network for as long as a session is **wanted** — not for
 * as long as it is connected (spec §5.1).
 *
 * Fail-closed means sitting with no network and waiting, and this is the thing
 * being waited on. Registering only while connected would make the no-network
 * case unrecoverable without a timer, which spec §2.4 forbids.
 *
 * The constructor parameter is [onNetworkLost], not `onLost`: the overridden
 * `ConnectivityManager.NetworkCallback.onLost(Network)` below has the same name
 * at a different arity, which compiles but reads as recursion to the next
 * person tracing this file.
 */
internal class NetworkMonitor(
    private val context: Context,
    private val onChanged: (Network) -> Unit,
    private val onNetworkLost: () -> Unit,
) {
    private val debouncer = NetworkTransitionDebouncer()

    /**
     * Guards [callback] against two threads calling [start]/[stop] at once — the
     * `tunnelSessionWanted` collector on its own dispatcher, and `TunnelService
     * .onDestroy()`'s direct [stop] call on whatever thread invokes it. Without
     * this, both could observe a non-null [callback] before either nulls it and
     * both would call `unregisterNetworkCallback` on the same instance. Harmless
     * today — that call is wrapped in [runCatching] — but not a race worth
     * leaving in a file that is otherwise careful about exactly this shape
     * (compare [space.getsub.service.TunnelService]'s own `lock`).
     */
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        synchronized(lock) {
            if (callback != null) return
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            // Priming with whatever is already active before registering is what keeps
            // registerDefaultNetworkCallback's immediate onAvailable replay of that same
            // network from reading as a transition — see NetworkTransitionDebouncer.prime.
            // A null activeNetwork (no connectivity yet) is left unprimed on purpose: the
            // first real onAvailable is then a genuine nothing-to-something transition and
            // must reconcile, whether the session became wanted before or after a network
            // actually exists.
            manager.activeNetwork?.let { active -> debouncer.prime(active.networkHandle) }
            val registered =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (debouncer.shouldReconcile(network.networkHandle, SystemClock.elapsedRealtime())) {
                            onChanged(network)
                        }
                    }

                    override fun onLost(network: Network) {
                        // Before the callback, not after: onNetworkLost cancels the
                        // pending retry timer, so from here until something is
                        // accepted by the debouncer there is nothing else left to
                        // resume this session. See NetworkTransitionDebouncer.reset.
                        debouncer.reset()
                        onNetworkLost()
                    }
                }
            manager.registerDefaultNetworkCallback(registered)
            callback = registered
        }
    }

    fun stop() {
        synchronized(lock) {
            val registered = callback ?: return
            callback = null
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            // Unregistering a callback that was never registered throws; guarded by
            // the null check above, and swallowed here because a service tearing down
            // must not crash on cleanup (§5.4).
            runCatching { manager.unregisterNetworkCallback(registered) }
        }
    }
}
