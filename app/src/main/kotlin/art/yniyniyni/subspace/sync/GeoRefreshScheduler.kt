// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.sync

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.GeoInstallRequest
import art.yniyniyni.subspace.core.data.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

internal const val GEO_REFRESH_WORK_NAME = "geo-refresh"

private const val TAG = "GeoRefreshScheduler"

/** The scheduled cadence. The 7-day freshness cap lives in [GeoAssetRepository.isDueForRefresh], not here. */
private const val GEO_REFRESH_INTERVAL_DAYS = 1L

/**
 * The scheduled-refresh decision logic, deliberately factored out of [GeoRefreshScheduler] so it
 * needs no [WorkManager] and can run under a plain JVM test (`GeoRefreshDecisionsTest`) rather
 * than only the instrumented one (`GeoRefreshSchedulerTest`) that exercises [reschedule]'s
 * constraints. [dueFiles] and [install] are injected as lambdas for the same reason
 * [GeoAssetRepository]'s own `download` and `clock` are: it keeps this class testable without a
 * real repository or the network.
 */
internal class GeoRefreshDecisions(
    private val dueFiles: suspend () -> List<GeoInstallRequest>,
    private val install: suspend (GeoInstallRequest) -> Unit,
) {
    /**
     * Installs every file [dueFiles] returns. Never throws (§10.4).
     *
     * That guarantee cannot rest on [install] alone. [GeoAssetRepository.install] itself never
     * throws — a failure is already recorded on the `geo_assets` row as `lastFailure` and
     * surfaced in the UI — but production's [dueFiles] wraps `GeoAssetRepository.observeAll()`
     * plus a `GeoAssetRepository.isDueForRefresh` Room read *per asset*, neither of which carries
     * that same promise: a locked or corrupt database throws there, before a single [install] call
     * happens. Review round 2, Important 4 caught this: an uncaught throw here would propagate out
     * of `GeoRefreshWorker.doWork()`, and — because that worker's `reschedule()` call sits in the
     * matching `finally` and itself does its own Room read (`onMetered()`) — a *second* failure
     * there would silently replace the first, the same way `SubscriptionRefreshWorker`'s KDoc
     * warns reporting worker failure would invite WorkManager to retry a URL that is simply wrong.
     * Both [dueFiles] and each [install] call are therefore caught individually below, narrowly,
     * with [CancellationException] always rethrown so genuine cancellation is never mistaken for
     * an ordinary failure. One failing file does not stop the rest of [dueFiles]' list from being
     * attempted.
     */
    @Suppress("TooGenericExceptionCaught") // Deliberate backstop — see the KDoc above.
    suspend fun refreshDue() {
        val due =
            try {
                dueFiles()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Exception) {
                emptyList()
            }
        due.forEach { request ->
            try {
                install(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Exception) {
                // Deliberately silent, not merely uncrashing: this class has no Android Log
                // available to it (it is the plain-JVM-testable half of GeoRefreshScheduler,
                // and GeoRefreshDecisionsTest exercises exactly this branch without a device),
                // and production's own `install` already logs the outcome per file — see
                // GeoRefreshModule.geoRefreshScheduler. An exception reaching here at all means
                // that logging itself was bypassed, which is already the unexpected case this
                // catch exists for.
            }
        }
    }

    /**
     * Installs [request] immediately, without consulting [dueFiles] at all.
     *
     * §A.5: the 7-day cap [dueFiles] applies exists to stop a chatty profile hammering a CDN, not
     * to tell the device's owner no. A manual "Update now" always runs, and — unlike [refreshDue]
     * — is allowed to propagate a failure: it is not reached from `GeoRefreshWorker`, so there is
     * no WorkManager retry policy for a swallowed exception to misdirect, and a caller-visible
     * failure is exactly what a user-initiated action needs so it can be reported.
     */
    suspend fun refreshNow(request: GeoInstallRequest) {
        install(request)
    }
}

/**
 * Keeps a daily periodic job installing whichever geo databases are due, and exposes a manual
 * override that bypasses both the cap and the metered constraint.
 *
 * Follows [RefreshScheduler]'s shape: [reschedule] runs from [GeoRefreshWorker]'s `finally` so a
 * crash or cancellation mid-refresh does not end the chain — a scheduler that stops scheduling is
 * indistinguishable from a feature that quietly stopped working.
 *
 * Unlike [RefreshScheduler], this does not self-reschedule to the next due instant: the cadence
 * is a fixed daily period, and the 7-day freshness cap that actually matters is enforced per-file
 * inside [GeoAssetRepository.isDueForRefresh], not by varying the period. [reschedule] only ever
 * needs to run again when the metered setting changes or the periodic job needs re-establishing
 * after a crash.
 */
internal class GeoRefreshScheduler(
    private val workManager: WorkManager,
    dueFiles: suspend () -> List<GeoInstallRequest>,
    install: suspend (GeoInstallRequest) -> Unit,
    private val onMetered: suspend () -> Boolean,
) {
    private val decisions = GeoRefreshDecisions(dueFiles, install)

    /** Installs every geo database due for a refresh. Never throws — see [GeoRefreshDecisions.refreshDue]. */
    suspend fun refreshDue() = decisions.refreshDue()

    /** Installs [request] now, ignoring both the cap and the metered constraint (§A.5). */
    suspend fun refreshNow(request: GeoInstallRequest) = decisions.refreshNow(request)

    /**
     * Replaces the periodic geo-refresh job with one whose network constraint reflects the
     * current metered setting.
     *
     * Runs under [NonCancellable] for the same reason [RefreshScheduler.reschedule] does: called
     * from [GeoRefreshWorker]'s `finally`, it must still reach `enqueueUniquePeriodicWork` even
     * when the worker's `Job` has already been cancelled (WorkManager cancels a `CoroutineWorker`
     * outright once it exceeds its execution ceiling).
     *
     * [ExistingPeriodicWorkPolicy.UPDATE], not `REPLACE`: the point of calling this again is
     * usually that the metered setting changed, not that the cadence itself moved, so the existing
     * periodic chain's next-run time is preserved rather than restarted.
     */
    suspend fun reschedule() {
        withContext(NonCancellable) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (onMetered()) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                GEO_REFRESH_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<GeoRefreshWorker>(GEO_REFRESH_INTERVAL_DAYS, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}

/**
 * The files a scheduled run should refresh: every installed geo asset [GeoAssetRepository]
 * considers due, re-fetched from the same [GeoInstallRequest] it was last installed from.
 *
 * Not the source catalogue (`GeoSourceCatalogue`) — a scheduled refresh re-downloads whatever is
 * already on disk from wherever it came from, including a user-added source the catalogue does
 * not know about.
 */
private suspend fun dueGeoRequests(
    repository: GeoAssetRepository,
    now: Long,
): List<GeoInstallRequest> =
    repository
        .observeAll()
        .first()
        .filter { asset -> repository.isDueForRefresh(asset.fileName, now) }
        .map { asset ->
            GeoInstallRequest(fileName = asset.fileName, sourceUrl = asset.sourceUrl, geoType = asset.geoType)
        }

/**
 * Wires [GeoRefreshScheduler]'s lambdas to real dependencies.
 *
 * A `@Provides` factory, not `@Inject` on the class itself: Hilt has no way to construct the
 * `suspend () -> ...` function types the constructor takes, the same reason [WorkManagerModule]
 * exists for [WorkManager] itself.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object GeoRefreshModule {
    @Provides
    @Singleton
    fun geoRefreshScheduler(
        workManager: WorkManager,
        geoAssets: GeoAssetRepository,
        settings: SettingsRepository,
    ): GeoRefreshScheduler =
        GeoRefreshScheduler(
            workManager = workManager,
            dueFiles = { dueGeoRequests(geoAssets, System.currentTimeMillis()) },
            install = { request ->
                // §5.6: the filename is shape, not a secret — the source URL that goes with it
                // is, and stays out of this line. Without this, a scheduled run that installed
                // nothing (dueFiles() came back empty — the ordinary case, most days) is
                // indistinguishable in the log from one that never ran at all, which is exactly
                // the silent-failure shape the bootstrap fix (Finding 2) exists to catch earlier.
                val result = geoAssets.install(request)
                Log.d(TAG, "geo refresh: ${request.fileName} -> $result")
            },
            onMetered = { settings.geoRefreshOnMetered.first() },
        )
}
