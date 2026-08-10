// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
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
    // Declaration order matters and is not enforced by the compiler: Hilt's generated
    // member-injector assigns fields in the order they're declared, and RefreshScheduler's
    // injected WorkManager is bound via WorkManager.getInstance(context) (WorkManagerModule),
    // which reads workManagerConfiguration — and therefore hiltWorkerFactory — the first time
    // it's called. hiltWorkerFactory must stay declared above refreshScheduler; swapping them
    // (or a Hilt codegen change that stops honouring declaration order) surfaces as a
    // `lateinit property hiltWorkerFactory has not been initialized` crash on launch, not a
    // compile error.
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
            applicationScope.launch { refreshScheduler.rescheduleOnChanges() }
            registerActivityLifecycleCallbacks(ForegroundCallbacks(::onMovedToForeground))
        }
    }

    /**
     * Spec §8's on-open trigger.
     *
     * This used to run from [onCreate], which is *process* creation, not app open. Every warm
     * start — back from recents, back from the launcher, back from another app — reuses a live
     * process and never called it. Combined with the interval gate that
     * [RefreshScheduler.refreshDue] used to apply first, that made the "Refresh when app opens"
     * row inert in both halves at once: it could not fire on most opens, and on the opens where
     * it did fire it could only suppress a refresh the interval trigger was already doing.
     * M4's device run reported it as "doesn't work at all", which was accurate.
     *
     * reschedule() runs in a finally for the same reason [art.yniyniyni.subspace.sync.SubscriptionRefreshWorker]'s
     * doWork() does: a crash mid-sync must not leave the one pending job unscheduled — that would
     * silently strand the whole feature until the app is opened again. reschedule() is
     * NonCancellable internally, so this holds even if [applicationScope] were cancelled
     * mid-refresh.
     */
    private fun onMovedToForeground() {
        applicationScope.launch {
            try {
                refreshScheduler.refreshDue(onOpen = true)
            } finally {
                refreshScheduler.reschedule()
            }
        }
    }
}

/**
 * Calls [onForeground] each time the app becomes visible, and not once per activity.
 *
 * Counting started activities is what distinguishes "the user opened the app" from "the app
 * rotated" or "one screen handed off to the next": a configuration change or an in-app navigation
 * stops one activity and starts another, so the count dips to zero only when the app actually
 * leaves the foreground. The 0 -> 1 edge therefore fires on cold start *and* on every return from
 * background, which is exactly the set of moments the on-open trigger means.
 *
 * `androidx.lifecycle:lifecycle-process` offers `ProcessLifecycleOwner` for this, and is
 * deliberately not used: it is a new dependency (§10.7 justification, THIRD_PARTY.md entry) for
 * one callback the platform already provides, and its ON_START is debounced by a 700 ms handler
 * delay this code has no need to reason about.
 */
private class ForegroundCallbacks(
    private val onForeground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities > 0) startedActivities--
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
