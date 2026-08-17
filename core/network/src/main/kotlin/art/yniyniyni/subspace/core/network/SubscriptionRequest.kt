// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

/**
 * Everything [SubscriptionFetcher] needs to perform one subscription fetch.
 *
 * @property url the subscription URL. A secret (§5.6) — never logged.
 * @property hwidEnabled §A.4.1: on by default. Off is a user choice that breaks
 *   limit-enabled providers, and the resulting failure must say so ([FetchFailure.HwidRequired]).
 * @property userAgentOverride §A.4.2's per-subscription override.
 * @property timeoutSeconds documented range 5–15, default 9. Supplied by the
 *   caller because it arrives as a directive delivered *by* the fetch it
 *   configures — the previous fetch's value, or the default on first contact.
 * @property proxyPort the tunnel's loopback HTTP proxy, or null to fetch
 *   directly. Defaults to null so every pre-existing call site keeps the old
 *   behaviour unchanged.
 */
public data class SubscriptionRequest(
    val url: String,
    val hwidEnabled: Boolean,
    val userAgentOverride: String?,
    val timeoutSeconds: Int,
    val proxyPort: Int? = null,
) {
    // §5.6: url is a secret. The generated data-class toString() would print
    // it verbatim, which is exactly the "reaches a log line" failure mode
    // this class exists to avoid — this is a structural guard against a
    // future `request.toString()` in a debug log, not a fix for a leak that
    // exists today. The other fields are not secrets and stay visible: they
    // are what makes a logged instance useful for debugging at all.
    //
    // proxyPort is deliberately not printed either — not because it is a
    // secret (§5.6: a port is not one) but because adding it here would be
    // scope creep on a redaction override that exists for exactly one
    // reason. Leaving it off keeps the printed shape stable for anyone
    // already relying on it in a log line.
    override fun toString(): String =
        "SubscriptionRequest(url=<redacted>, hwidEnabled=$hwidEnabled, " +
            "userAgentOverride=$userAgentOverride, timeoutSeconds=$timeoutSeconds)"
}
