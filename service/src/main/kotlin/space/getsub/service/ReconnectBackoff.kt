// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/** The first retry's delay. Prompt: most retryable failures here are resource contention. */
internal const val BACKOFF_BASE_MILLIS: Long = 1_000L

/** The ceiling. See [ReconnectBackoff]. */
internal const val BACKOFF_CAP_MILLIS: Long = 60_000L

/** Doubling past this would exceed the cap anyway; stopping here keeps the shift in range. */
private const val MAX_DOUBLINGS: Int = 6

/**
 * How long to wait before the next reconnect attempt (spec §2.4).
 *
 * **This says nothing about whether to schedule at all.** When no network is
 * available the service schedules nothing and waits on the `NetworkCallback`
 * instead — a retry timer running in Doze is how §11's six-hour screen-off row
 * fails. `reconcile` enforces that; this only answers "how long".
 */
internal object ReconnectBackoff {
    fun delayMillisFor(attempt: Int): Long {
        val doublings = (attempt.coerceAtLeast(1) - 1).coerceAtMost(MAX_DOUBLINGS)
        val delay = BACKOFF_BASE_MILLIS shl doublings
        return delay.coerceAtMost(BACKOFF_CAP_MILLIS)
    }
}
