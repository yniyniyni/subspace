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

/**
 * The address a TUN built for [plannedAddress] will advertise: the planned one,
 * or [fallbackAddress] when the plan has none to offer.
 *
 * This is the *planned* answer, not necessarily the one Android accepted —
 * [addDnsServerOrFallback] falls back again if the builder rejects the literal.
 * That asymmetry is deliberate and it is the safe direction: rejection is a
 * function of the address, so two plans resolving to the same planned address
 * also resolve to the same advertised one. The comparison in
 * [retainedTunKeepsAdvertisedDns] can therefore rebuild a TUN it did not need
 * to, and can never keep one it should have rebuilt — and only the second of
 * those is a DNS leak.
 */
internal fun advertisedTunDnsAddress(
    plannedAddress: String?,
    fallbackAddress: String,
): String = plannedAddress ?: fallbackAddress

/**
 * Whether a restart that keeps the existing TUN still advertises the right
 * resolver (spec §5.2, lever 1).
 *
 * A restart that keeps the TUN never re-runs `Builder.establish()`, so the
 * interface goes on advertising whatever it was built with while the *core* is
 * rebuilt from current settings. That is fine while both sides name the same
 * address and a leak when they do not: a plan that has become null emits no
 * port-53 hijack, so a query to the pinned address is routed by the rule set
 * like any other packet — and the pinned address is typically the domestic
 * resolver, which a `geoip:<country>` DIRECT rule sends out in the clear.
 *
 * [pinned] is null when nothing is recorded for the live interface. That is
 * treated as "unknown", not "unchanged", so the caller rebuilds: guessing wrong
 * here costs one rebuild, and guessing wrong the other way costs every lookup.
 */
internal fun retainedTunKeepsAdvertisedDns(
    pinned: String?,
    next: String,
): Boolean = pinned != null && pinned == next
