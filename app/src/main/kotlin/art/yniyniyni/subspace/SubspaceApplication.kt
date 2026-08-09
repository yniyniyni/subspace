// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.app.Application
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import art.yniyniyni.subspace.sync.RefreshScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The name of the process this code is executing in.
 *
 * `Application.getProcessName()` only exists from API 28 and this app's `minSdk` is 26, so the older
 * path reads `/proc/self/cmdline`, which the platform sets to the process name at fork time. Same
 * technique `:core:data`'s `WriterService` uses for its own multi-process test, per the M3 precedent
 * this file follows rather than inventing a second mechanism.
 */
private fun currentProcessName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        File("/proc/self/cmdline").readText().trim { it <= ' ' }
    }

/**
 * `:bg` is `TunnelService`'s process (§3) — this [Application] class is instantiated in both, so any
 * work that must run exactly once per app launch (not once per process) has to check which one it is
 * in before doing anything.
 */
private fun isMainProcess(): Boolean = !currentProcessName().endsWith(":bg")

@HiltAndroidApp
class SubspaceApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    @Inject
    internal lateinit var refreshScheduler: RefreshScheduler

    // Owned by this Application instance, not GlobalScope (§12) — it lives exactly as long as the
    // process does, which is what the launch-time refresh below needs.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Only in :main (see isMainProcess's KDoc) — :bg is TunnelService's process and has no
        // business refreshing subscriptions or touching WorkManager.
        if (isMainProcess()) {
            applicationScope.launch {
                // Spec §8's "on launch" trigger: refreshDue() already only syncs subscriptions
                // whose own interval has elapsed, so this call covers both "refresh what's overdue
                // right now" and re-establishes the one pending job for whatever's next.
                //
                // reschedule() runs in a finally for the same reason SubscriptionRefreshWorker.doWork()
                // does: a crash mid-sync here must not leave the one pending job unscheduled — that
                // would silently strand the whole feature until the app is opened again.
                try {
                    refreshScheduler.refreshDue()
                } finally {
                    refreshScheduler.reschedule()
                }
            }
        }
    }
}
