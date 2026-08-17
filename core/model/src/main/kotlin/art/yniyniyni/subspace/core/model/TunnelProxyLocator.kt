// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

/**
 * Where the running tunnel's loopback HTTP proxy is, if there is one.
 *
 * An interface in `:core:model` because `:core:data` needs it (to route
 * subscription and geo fetches through the tunnel, spec §5.4) and cannot
 * depend on `:service` (§4). `:app` implements it over `TunnelClient`'s
 * published state — the only place both are visible.
 *
 * §5.5 still holds: an implementation reads the state the service publishes,
 * and never keeps a local guess about whether the tunnel is up.
 *
 * A `fun interface` so test/fixture call sites in other modules can supply
 * `TunnelProxyLocator { null }` without a throwaway class.
 */
public fun interface TunnelProxyLocator {
    /**
     * The loopback port to proxy through, or null when no tunnel is up, nothing
     * is bound, or the session carries no HTTP inbound.
     *
     * **Null is ordinary, not an error.** A background refresh with no bound
     * service gets null and fetches directly, which is what keeps a broken
     * tunnel from also breaking subscription updates.
     */
    public fun httpProxyPortOrNull(): Int?
}
