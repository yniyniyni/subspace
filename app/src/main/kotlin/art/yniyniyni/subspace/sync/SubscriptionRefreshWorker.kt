// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Syncs whichever subscriptions are due, then schedules the next wake-up.
 *
 * Returns [Result.success] even when individual syncs fail. A failed fetch is
 * already recorded on the subscription row as a `lastFetchStatus` the UI
 * renders (spec §7); reporting it as worker failure would additionally invite
 * WorkManager's backoff to retry a subscription whose URL is simply wrong.
 *
 * The reschedule runs in a `finally` so a crash mid-sync does not end the chain
 * — a scheduler that stops scheduling is indistinguishable from a subscription
 * feature that quietly stopped working.
 */
@HiltWorker
internal class SubscriptionRefreshWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: RefreshScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            scheduler.refreshDue()
            Result.success()
        } finally {
            scheduler.reschedule()
        }
}
