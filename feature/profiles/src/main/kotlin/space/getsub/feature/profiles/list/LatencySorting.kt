// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import space.getsub.core.data.StoredProfile
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult

/**
 * Band boundaries for [SortOrder.Fastest].
 *
 * Both sit far above any delay a measurement can report — libXray's own timeout
 * sentinel is 11 000 ms, and even that never reaches this code, since a failed
 * ping throws before it becomes a result. Sorting by a key rather than by a
 * comparator chain keeps the three bands in one readable expression.
 */
private const val BAND_UNMEASURED = 1_000_000L
private const val BAND_FAILED = 2_000_000L

/**
 * Maps a `subscriptions-sort-type` directive value onto an order.
 *
 * Returns null for anything unrecognised — including a blank or absent value —
 * so the caller falls back to the user's own default. A provider sending
 * nonsense must not silently rearrange the list into an order nobody chose.
 *
 * [SortOrder.LastUsed] has no directive equivalent and stays user-only.
 */
internal fun sortOrderFromDirective(value: String?): SortOrder? =
    when (value) {
        "without" -> SortOrder.AsListed
        "ping" -> SortOrder.Fastest
        "alphabet" -> SortOrder.Alphabetical
        else -> null
    }

/**
 * Orders one group's rows.
 *
 * [SortOrder.Fastest] ranks measured ascending, then unmeasured, then failed.
 * Failed last because a server that did not answer is not a fast server;
 * unmeasured ahead of failed so a cold list keeps its provider-given order
 * instead of being scrambled. `sortedBy` is stable, so ties inside each band
 * retain their as-listed positions — which is what makes the unmeasured band
 * read as "untouched" rather than "shuffled".
 *
 * Note this compares within one group only. Two groups can be measured in
 * different modes, since `ping-type` is per subscription, and a TCP handshake is
 * not comparable to an HTTP HEAD.
 */
internal fun List<StoredProfile>.sortedFor(
    order: SortOrder,
    latencies: Map<Long, LatencyResult>,
): List<StoredProfile> =
    when (order) {
        // Already ORDER BY position, id from the DAO — the user's own arrangement.
        SortOrder.AsListed -> this
        SortOrder.Alphabetical -> sortedBy { it.name.lowercase() }
        // Never-connected rows have no lastConnectedAt; MIN_VALUE sorts them
        // last rather than first among ties, which is a stable sort — so two
        // never-connected rows keep their relative AsListed order.
        SortOrder.LastUsed -> sortedByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
        SortOrder.Fastest ->
            sortedBy { profile ->
                val result = latencies[profile.id]
                when {
                    result == null -> BAND_UNMEASURED
                    // delayMillis is read on this branch only. On any other
                    // outcome it is a placeholder zero, and sorting by it would
                    // put every failure at the top (§10.1).
                    result.outcome == LatencyOutcome.OK -> result.delayMillis.toLong()
                    else -> BAND_FAILED
                }
            }
    }
