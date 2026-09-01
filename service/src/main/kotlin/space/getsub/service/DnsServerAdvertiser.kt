// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/**
 * Offers a planned resolver address to a TUN builder and falls back when Android
 * rejects the plan's address literal.
 *
 * The builder is supplied as a lambda because [android.net.VpnService.Builder]
 * cannot be constructed in a JVM unit test. Keeping the Android call at the
 * boundary lets the rejection/fallback contract run as a real unit test.
 *
 * @return true when [plannedAddress] was rejected and [fallbackAddress] was used.
 */
internal fun addDnsServerOrFallback(
    plannedAddress: String?,
    fallbackAddress: String,
    addDnsServer: (String) -> Unit,
): Boolean {
    if (plannedAddress == null) {
        addDnsServer(fallbackAddress)
        return false
    }

    return try {
        addDnsServer(plannedAddress)
        false
    } catch (_: IllegalArgumentException) {
        addDnsServer(fallbackAddress)
        true
    }
}
