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
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val registered =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (debouncer.shouldReconcile(network.networkHandle, SystemClock.elapsedRealtime())) {
                        onChanged(network)
                    }
                }

                override fun onLost(network: Network) {
                    onNetworkLost()
                }
            }
        manager.registerDefaultNetworkCallback(registered)
        callback = registered
    }

    fun stop() {
        val registered = callback ?: return
        callback = null
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Unregistering a callback that was never registered throws; guarded by
        // the null check above, and swallowed here because a service tearing down
        // must not crash on cleanup (§5.4).
        runCatching { manager.unregisterNetworkCallback(registered) }
    }
}
