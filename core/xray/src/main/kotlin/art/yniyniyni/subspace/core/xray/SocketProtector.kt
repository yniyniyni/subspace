// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

/**
 * Marks a socket to bypass the VPN route.
 *
 * ARCHITECTURE.md §5.1: every socket libXray opens to the remote server must be
 * passed to `VpnService.protect(fd)`. Without it the outbound packet is routed
 * back into the TUN interface — an infinite loop with no traffic, no error, and
 * rising CPU.
 *
 * This interface exists so `:core:xray` never imports `VpnService`. `:service`
 * supplies the implementation; JVM tests supply a fake.
 *
 * **Returning false does not stop anything.** libXray's Go wrapper discards this
 * value (see `docs/agent/research/libxray-api.md` §2), so a failed protect is
 * invisible to the core and surfaces only as §5.1's symptom. The implementation
 * must log the failure itself — redacted, §5.6 — or nothing will.
 *
 * §10.2: this looks like boilerplate and is load-bearing. Do not simplify it.
 */
public fun interface SocketProtector {
    /** @return true if the fd was successfully protected. */
    public fun protect(fd: Int): Boolean
}
