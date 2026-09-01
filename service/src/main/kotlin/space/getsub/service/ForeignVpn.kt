// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether some **other** app's VPN currently holds the default route.
 *
 * A latency measurement taken in that state is meaningless: the socket enters
 * that client's tunnel and the number is the round trip to its local endpoint,
 * not to the server. A device run under another client's proxy reported 1 ms for
 * a server in Singapore — roughly a hundredth of the physical minimum.
 *
 * **Escaping it is not possible, and should not be.** `VpnService.protect()`
 * exempts a socket from *this* app's tunnel only; Android applies VPN routing
 * per-UID and lets the owning app alone mark a socket to bypass it. An earlier
 * attempt here bound the socket to a `NOT_VPN` transport instead, on the theory
 * that selecting a network would sidestep the routing. The device disproved it —
 * still 1 ms — and the theory was wrong anyway: an app that could unilaterally
 * leave a user-chosen VPN would defeat the purpose of VPNs. That code is gone
 * rather than left in place claiming to work.
 *
 * So the honest response is to decline to measure and say why. Rendering the
 * number we can obtain would put a plausible, wrong reading on screen, which is
 * exactly the failure ARCHITECTURE.md §10.1 describes.
 */
internal class ForeignVpn(private val context: Context) {
    /**
     * @param ownTunnelActive whether *our* `TunnelService` currently holds a
     *   session. Only one VPN can be active at a time, so a VPN on the default
     *   route while this is true is ours — and measuring through it works, which
     *   a device run confirmed.
     */
    fun holdsDefaultRoute(ownTunnelActive: Boolean): Boolean {
        if (ownTunnelActive) return false
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
}
