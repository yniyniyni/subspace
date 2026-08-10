// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

/**
 * The seam `:core:data`'s sync pipeline fetches through.
 *
 * Exists so `SubscriptionSyncer`'s test (Task 11) can script a [FetchOutcome]
 * without a live server — MockWebServer stays in `SubscriptionFetcherTest`,
 * where it belongs. [SubscriptionFetcher] is the only production
 * implementation; Hilt binds it.
 */
public fun interface SubscriptionSource {
    public suspend fun fetch(request: SubscriptionRequest): FetchOutcome
}
