// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Installs whichever geo databases are due, then re-establishes the daily periodic job.
 *
 * Returns [Result.success] even when individual installs fail. A failed install is already
 * recorded on the `geo_assets` row as a `lastFailure` the UI renders; reporting it as worker
 * failure would additionally invite WorkManager's backoff to retry a URL that is simply wrong.
 *
 * The reschedule runs in a `finally` so a crash mid-refresh does not end the chain — a scheduler
 * that stops scheduling is indistinguishable from a geo-refresh feature that quietly stopped
 * working. Mirrors [SubscriptionRefreshWorker] exactly.
 */
@HiltWorker
internal class GeoRefreshWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: GeoRefreshScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            scheduler.refreshDue()
            Result.success()
        } finally {
            scheduler.reschedule()
        }
}
