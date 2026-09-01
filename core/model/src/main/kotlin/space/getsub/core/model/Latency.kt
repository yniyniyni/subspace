// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

/**
 * How a latency measurement is taken.
 *
 * There is deliberately no `ICMP`: raw sockets need root (Appendix D), and
 * `DirectiveRegistry` already rejects that value. Do not add it.
 *
 * There is also no separate `PROXY` (HTTP GET). libXray v26.7.11's
 * `nodep.MeasureDelay` hardcodes `http.NewRequest("HEAD", …)`, and `xray.Ping`
 * closes its throwaway instance via `defer` before a caller could borrow the
 * SOCKS port to issue a GET itself — see
 * `docs/agent/research/2026-08-10-libxray-ping-semantics.md` §4. A provider's
 * `ping-type: proxy` therefore maps to [PROXY_HEAD], and the UI labels it
 * "Proxy (HEAD)" so the alias is visible rather than silent.
 */
public enum class PingMode { TCP, PROXY_HEAD }

/**
 * Maps a `ping-type` directive value, or a stored setting, onto a [PingMode].
 *
 * Anything unrecognised — including null — falls back to [PingMode.TCP].
 *
 * TCP is the default because it is the number a user can check against anything
 * else. A device run compared it against Happ and Speedtest across five servers
 * on three continents and it agreed within a few milliseconds each time
 * (Amsterdam 38/39, Stockholm 20/19/20, Frankfurt 42/41, Newark 121/124,
 * Singapore 196/204). It is also seconds rather than a minute for a large group,
 * and costs no measurable data.
 *
 * What it gives up: a server that accepts the TCP connection and then rejects
 * the handshake still reads fast. [PROXY_HEAD] is the mode that proves the proxy
 * actually carries traffic, and the settings copy says so at the point of choice.
 */
public fun pingModeFrom(value: String?): PingMode =
    when (value) {
        "tcp" -> PingMode.TCP
        // The alias. `proxy` means GET, which v26.7.11 cannot issue.
        "proxy", "proxy-head" -> PingMode.PROXY_HEAD
        else -> PingMode.TCP
    }

/**
 * Why a measurement ended.
 *
 * A typed value, never a message string: libXray's ping errors quote the config
 * that produced them, which carries the server address, UUID and REALITY key
 * (§5.6). The UI renders its own text from `strings.xml` off this enum.
 */
public enum class LatencyOutcome {
    OK,
    TIMEOUT,
    UNREACHABLE,
    UNSUPPORTED,
    CANCELLED,

    /**
     * Another app's VPN holds the default route, so nothing measurable can be
     * obtained — the socket would enter that tunnel and time the route to it.
     *
     * A distinct outcome rather than a failure, because it is neither the
     * server's fault nor something a retry fixes, and because the alternative is
     * rendering the number we *can* get: a device run under another client's
     * proxy produced 1 ms for Singapore. Appended last on purpose — these
     * ordinals cross the AIDL boundary, so inserting one would renumber the rest.
     */
    FOREIGN_VPN,
}

/**
 * One measurement.
 *
 * [delayMillis] is meaningful **only** when [outcome] is [LatencyOutcome.OK],
 * and is `0` otherwise — not a substituted reading. Callers must branch on
 * [outcome] and never render [delayMillis] unconditionally: libXray reports a
 * failed ping as `delay = 10000` or `11000` (its `PingDelayError` /
 * `PingDelayTimeout` sentinels), and putting either on screen is §10.1's
 * invented-number failure with a plausible unit attached.
 */
public data class LatencyResult(
    val delayMillis: Int,
    val outcome: LatencyOutcome,
) {
    public companion object {
        public fun ok(delayMillis: Int): LatencyResult = LatencyResult(delayMillis, LatencyOutcome.OK)

        public fun failed(outcome: LatencyOutcome): LatencyResult = LatencyResult(0, outcome)
    }
}

/**
 * The resolved options for one measurement run.
 *
 * Resolved in `:main` before the AIDL call, so `:bg` never has to reach into
 * settings or directives to interpret a run.
 */
public data class LatencyOptions(
    val mode: PingMode,
    val timeoutSeconds: Int,
    val checkUrl: String,
)

/**
 * One profile, and the mode it is to be measured in.
 *
 * A carrier rather than two parallel lists: `ping-type` is scoped per
 * subscription (§A.1), so a single run can span groups measured differently, and
 * an id list beside a mode list is one filter or sort away from pairing the
 * wrong two. They travel as parallel arrays only across the AIDL boundary, where
 * primitives are the wire format — and are re-paired immediately on both sides.
 */
public data class LatencyTarget(
    val profileId: Long,
    val mode: PingMode,
)
