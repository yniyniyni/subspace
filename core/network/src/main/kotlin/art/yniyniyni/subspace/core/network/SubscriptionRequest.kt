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
 */
public data class SubscriptionRequest(
    val url: String,
    val hwidEnabled: Boolean,
    val userAgentOverride: String?,
    val timeoutSeconds: Int,
) {
    // §5.6: url is a secret. The generated data-class toString() would print
    // it verbatim, which is exactly the "reaches a log line" failure mode
    // this class exists to avoid — this is a structural guard against a
    // future `request.toString()` in a debug log, not a fix for a leak that
    // exists today. The other fields are not secrets and stay visible: they
    // are what makes a logged instance useful for debugging at all.
    override fun toString(): String =
        "SubscriptionRequest(url=<redacted>, hwidEnabled=$hwidEnabled, " +
            "userAgentOverride=$userAgentOverride, timeoutSeconds=$timeoutSeconds)"
}
