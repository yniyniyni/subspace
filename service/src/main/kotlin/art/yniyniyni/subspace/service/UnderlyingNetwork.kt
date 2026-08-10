// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Socket

private const val TAG = "UnderlyingNetwork"

/**
 * Routes a measurement socket onto the real Wi-Fi or cellular network, past any
 * VPN — **including a VPN belonging to another app**.
 *
 * `VpnService.protect()` is not enough on its own, and the reason is a platform
 * rule rather than a bug: it exempts a socket from *this* app's tunnel only. The
 * system does not let one VPN app punch through another's. So on a device where
 * some other client holds the tunnel, a protected socket still goes through that
 * client, and a latency measurement times the route to it rather than to the
 * server.
 *
 * That is not hypothetical. A device run with another client's proxy active
 * reported 1–6 ms for servers in Amsterdam, Newark and Singapore — the round trip
 * to the local tunnel endpoint, not to the servers.
 *
 * Binding to a network that is explicitly `NOT_VPN` sidesteps the whole
 * question: the socket leaves on that transport regardless of who owns the
 * default route.
 *
 * **What this cannot do**, and must not pretend to: if the other VPN is
 * always-on with "Block connections without VPN" enabled, the kernel drops
 * non-VPN traffic for the whole UID. Nothing an app does in userspace gets past
 * that, and nothing should — it is the guarantee the user asked the platform for.
 * A measurement simply fails there, which is the honest outcome.
 */
internal class UnderlyingNetwork(private val context: Context) {
    /**
     * @return true if [socket] was bound to a non-VPN network. False means the
     *   caller should fall back to `VpnService.protect`, which at least handles
     *   our own tunnel.
     */
    fun bind(socket: Socket): Boolean {
        val target =
            context.getSystemService(ConnectivityManager::class.java)?.firstNonVpnNetwork()
                ?: return false
        return try {
            target.bindSocket(socket)
            true
        } catch (e: java.io.IOException) {
            // Reported, not swallowed: falling back to protect() silently would
            // hide the case where measurements are quietly wrong again. The
            // class name carries no address, so this is §5.6-safe.
            Log.w(TAG, "bind to underlying network failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * `getAllNetworks` is deprecated from API 31, and there is no synchronous
     * replacement — the alternatives are a registered `NetworkCallback` with its
     * own lifecycle, which belongs to M7's network-transition work (§9) rather
     * than to a measurement helper.
     *
     * `VALIDATED` matters as much as `NOT_VPN`: a Wi-Fi network that has
     * associated but has no working internet would otherwise be chosen over a
     * cellular one that does, and every measurement would fail for a reason the
     * user cannot see.
     */
    @Suppress("DEPRECATION")
    private fun ConnectivityManager.firstNonVpnNetwork(): Network? =
        allNetworks.firstOrNull { candidate ->
            val capabilities = getNetworkCapabilities(candidate) ?: return@firstOrNull false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
}
