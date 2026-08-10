// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

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
 * Anything unrecognised — including null — falls back to [PingMode.PROXY_HEAD],
 * the mode that actually proves the proxy carries traffic rather than merely
 * that the port accepts a connection.
 */
public fun pingModeFrom(value: String?): PingMode =
    when (value) {
        "tcp" -> PingMode.TCP
        // The alias. `proxy` means GET, which v26.7.11 cannot issue.
        "proxy", "proxy-head" -> PingMode.PROXY_HEAD
        else -> PingMode.PROXY_HEAD
    }

/**
 * Why a measurement ended.
 *
 * A typed value, never a message string: libXray's ping errors quote the config
 * that produced them, which carries the server address, UUID and REALITY key
 * (§5.6). The UI renders its own text from `strings.xml` off this enum.
 */
public enum class LatencyOutcome { OK, TIMEOUT, UNREACHABLE, UNSUPPORTED, CANCELLED }

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
