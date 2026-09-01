// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

/**
 * Bounded so a genuinely exhausted ephemeral range fails fast rather than
 * spinning — the same shape as [ProxyHeadProbe]'s single retry, widened
 * slightly because this guards a stronger property (see [allocateDistinctPorts]).
 */
private const val MAX_ALLOCATE_DISTINCT_ATTEMPTS = 3

/**
 * Requests [count] free ports from [fetch], retrying when the result is not
 * distinct.
 *
 * `docs/agent/research/libxray-api.md` §5, quoting `nodep/port.go`: `GetFreePorts`
 * binds `localhost:0`, records the port, and **closes the listener before
 * opening the next one**. Nothing holds the earlier port between iterations, so
 * the kernel can hand the same number back inside a single call — the file's
 * own example is one `count = 2` call returning `[38411, 38411]`. Two Xray
 * inbounds sharing a port is a config the core rejects outright, so this checks
 * distinctness itself rather than trusting the call to provide it.
 *
 * A free function rather than a method on [XrayController], for the same
 * reason [XrayPingApi] is a separate interface from [ProxyHeadProbe]: the
 * underlying libXray call ([LibXrayInvoke], backed by native `LibXray.invoke`)
 * cannot run on the JVM, but the retry loop that consumes it can, with [fetch]
 * faked — see `PortAllocationTest`.
 *
 * @param fetch requests exactly [count] ports from the underlying source. May
 *   itself return duplicates or a short list; both are treated as a failed
 *   attempt and retried.
 * @throws XrayException when [count] distinct ports could not be obtained
 *   within [MAX_ALLOCATE_DISTINCT_ATTEMPTS] attempts.
 */
internal suspend fun allocateDistinctPorts(
    count: Int,
    fetch: suspend (Int) -> List<Int>,
): List<Int> {
    repeat(MAX_ALLOCATE_DISTINCT_ATTEMPTS) {
        val ports = fetch(count)
        if (ports.size == count && ports.toSet().size == count) return ports
    }
    throw XrayException("libXray could not return $count distinct free ports")
}
