// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import space.getsub.core.model.DnsState
import space.getsub.core.model.ProfileDns

/**
 * Maps a typed profile DNS block to its visible state.
 *
 * [sniffingEnabled] separates a working FakeDNS request from one the tunnel
 * must refuse. It is `true` for current UI callers: sniffing is not yet a
 * configurable setting (R12), while this pure mapper preserves the future
 * refusal behavior without duplicating it in the routing list and review sheet.
 */
internal fun dnsStateOf(
    dns: ProfileDns?,
    sniffingEnabled: Boolean,
): DnsState =
    when {
        dns == null -> DnsState.None
        dns.isInvalid -> DnsState.Invalid
        dns.fakeDns == true && !sniffingEnabled -> DnsState.NeedsSniffing
        else -> DnsState.Applied
    }
