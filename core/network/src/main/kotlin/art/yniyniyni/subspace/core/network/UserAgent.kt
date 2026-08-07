// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

/**
 * The User-Agent sent on subscription requests.
 *
 * ARCHITECTURE.md §A.4.2: Remnawave supports **response rules** — the panel
 * matches on request headers, notably `user-agent`, and serves a different
 * subscription format per client. An unrecognised UA can therefore yield the
 * wrong format entirely. A well-formed `<AppName>/<version>` is the requirement;
 * getting this project into the panel's recognised-client registry is M9's.
 *
 * The parser must never assume a format from the UA it sent — see
 * `SubscriptionFetcher`, which returns the raw body and headers and leaves
 * format detection to `:core:parser`, which sniffs the body.
 */
public object UserAgent {
    public fun default(versionName: String): String = "Subspace/$versionName"
}
