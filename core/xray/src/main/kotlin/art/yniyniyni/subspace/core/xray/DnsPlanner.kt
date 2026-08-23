// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsValidation
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet

/** One entry in the generated `dns.servers` array. */
public data class DnsServerSpec(
    public val address: String,
    public val domains: List<String> = emptyList(),
    public val skipFallback: Boolean = false,
) {
    /** §5.6: a resolver endpoint is a provider-chosen hostname. */
    override fun toString(): String =
        "DnsServerSpec(address=<redacted>, domains=${domains.size}, skipFallback=$skipFallback)"
}

/**
 * Everything the generator needs to emit a `dns` block and its routing rules.
 *
 * [directMatch] and [proxyMatch] are the addresses the two resolver-traffic rules
 * match on — spec §7.3 distinguishes the resolvers by **address** rather than by a
 * per-server `tag`, because whether `NameServerObject.tag` overrides `dns.tag` for
 * routing is unsourced (research §6) and this design needs no answer to it.
 */
public data class DnsPlan(
    public val servers: List<DnsServerSpec>,
    public val hosts: Map<String, String>,
    public val fakeDns: Boolean,
    public val directMatch: String?,
    public val proxyMatch: String?,
) {
    /** §5.6: hosts and resolver addresses never reach a log line. */
    override fun toString(): String =
        "DnsPlan(servers=${servers.size}, hosts=<redacted, ${hosts.size} entries>, " +
            "fakeDns=$fakeDns, matches=<redacted>)"

    /**
     * The address to hand `VpnService.Builder.addDnsServer` (§5.2, half two).
     *
     * Largely cosmetic while the port-53 hijack is in place, which is the point of
     * stating it: if the hijack ever fails to match, the bypass reaches the
     * resolver the user chose rather than a literal they never picked. A quieter
     * failure is not the goal; a less wrong one is.
     *
     * Null when the plan has only a DoH endpoint and no bootstrap IP — the TUN
     * needs an address literal, and the caller falls back to the app default.
     */
    public fun tunAdvertisedAddress(): String? =
        servers.firstNotNullOfOrNull { server -> server.address.takeIf(DnsValidation::isAddressLiteral) }
            ?: hosts.values.firstOrNull(DnsValidation::isAddressLiteral)
}

/**
 * Resolves profile-over-setting precedence into one plan.
 *
 * This is the seam the roadmap asks for: *"our generator will not be the only DNS
 * path once M7 lands, because a passthrough config brings its own `dns` block —
 * design this as one path among two."* A passthrough config bypasses the planner
 * rather than fighting it, and precedence lives here so M7 need not restate it.
 */
public object DnsPlanner {
    /**
     * The resolver the app ships with, and the value that makes [plan] return null.
     *
     * An alias for [DnsResolver.DEFAULT], which is where the literal actually
     * lives: `:core:data`'s `SettingsRepository` needs the same default and may
     * not depend on this module (ARCHITECTURE.md §4), so `:core:model` is the one
     * place that may declare it.
     */
    public val DEFAULT_SETTING: DnsResolver = DnsResolver.DEFAULT

    /**
     * Null when nothing asks for DNS — no profile block, and the setting at its
     * default. Spec §7.4: that null is what keeps the M1 config byte-identical,
     * and the M1 config is the one proven on hardware.
     *
     * Controller ruling R2: a *present* profile block that asks for nothing —
     * no resolver, no hosts, and `fakeDns` not explicitly `true` — is treated as
     * absent for this purpose too. Without this, a profile carrying only
     * `{"FakeDNS":"false"}` (or an empty `DnsHosts`) would count as "effective"
     * and force the whole new code path (hijack rule, `dns-out`, rewritten
     * sniffing) onto a profile that requested no DNS behaviour at all. Note this
     * checks [ProfileDns.isInvalid], never `==`: [ProfileDns.INVALID] is itself a
     * default-constructed `ProfileDns()`, so structural equality would treat
     * every "asks for nothing" block as equal to the sentinel.
     */
    public fun plan(
        profileDns: ProfileDns?,
        setting: DnsResolver,
        routing: RoutingRuleSet?,
        sniffingEnabled: Boolean,
    ): DnsPlan? {
        val candidate = profileDns?.takeUnless { it.isInvalid }
        val asksForNothing =
            candidate != null &&
                !candidate.hasResolver &&
                candidate.hosts.isEmpty() &&
                candidate.fakeDns != true
        val effective = candidate?.takeUnless { asksForNothing }
        val nothingToDo = effective == null && setting == DEFAULT_SETTING
        if (nothingToDo) return null

        val directSites = routing?.bucket(RouteOutcome.DIRECT)?.sites.orEmpty()
        val servers = buildServers(effective, setting, directSites)

        // A single return keeps ReturnCount within the project's detekt budget:
        // an empty server list is "nothing to plan", same as the guard above.
        return servers.takeIf { it.isNotEmpty() }?.let {
            val domesticMatch = effective?.domestic?.routingMatch()
            val remoteMatch = effective?.remote?.routingMatch()
            DnsPlan(
                servers = it,
                hosts = effective?.hosts.orEmpty(),
                fakeDns = (effective?.fakeDns ?: false) && sniffingEnabled,
                directMatch = domesticMatch,
                // Collapse when both resolvers are one server: two contradictory
                // rules on the same match is not a configuration, it is a coin flip.
                proxyMatch = remoteMatch?.takeIf { match -> match != domesticMatch },
            )
        }
    }

    private fun buildServers(
        dns: ProfileDns?,
        setting: DnsResolver,
        directSites: List<String>,
    ): List<DnsServerSpec> {
        if (dns == null || !dns.hasResolver) {
            return listOfNotNull(setting.xrayAddress()?.let { DnsServerSpec(it) })
        }

        val servers = mutableListOf<DnsServerSpec>()

        // Domestic first: it is the scoped one, and Xray takes the first server
        // whose domains match. Checked in this order deliberately: a domestic-only
        // profile (no remote) must stay unscoped even when a direct bucket exists,
        // because scoping it would leave every other query unanswered — there is
        // no remote server after it to fall back to. Only once a remote server is
        // present to catch the rest does scoping to the direct bucket make sense;
        // and with no direct bucket to scope to, the domestic server is omitted
        // entirely rather than emitted unscoped ahead of remote, which would
        // silently answer everything and invert the profile (spec §7.2.1).
        dns.domestic?.xrayAddress()?.let { address ->
            when {
                dns.remote == null -> servers += DnsServerSpec(address)
                directSites.isNotEmpty() ->
                    servers += DnsServerSpec(address, domains = directSites, skipFallback = true)
                else -> Unit
            }
        }
        dns.remote?.xrayAddress()?.let { address -> servers += DnsServerSpec(address) }
        return servers
    }
}
