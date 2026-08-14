// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import art.yniyniyni.subspace.sync.GeoRefreshScheduler
import art.yniyniyni.subspace.sync.RefreshScheduler
import dagger.Lazy
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

    /**
     * Deliberately [Lazy], and this is a correctness requirement rather than a performance one.
     *
     * Constructing [RefreshScheduler] eagerly resolves its `WorkManager` binding, whose provider
     * calls `WorkManager.getInstance(context)`; that reads [workManagerConfiguration] on first
     * access, which dereferences [hiltWorkerFactory]. An eager field made that whole chain run
     * *during* member injection, so startup only worked because Hilt's generated injector happens
     * to assign fields in declaration order and this field happens to be declared second. A
     * reorder — or a codegen change, or a second eager dependency that reaches WorkManager first —
     * turns into `lateinit property hiltWorkerFactory has not been initialized` at launch, not a
     * compile error.
     *
     * [Lazy] moves the resolution to the first `get()` inside [onCreate], after `super.onCreate()`
     * has finished injecting. The invariant worth remembering: **nothing may call
     * `WorkManager.getInstance` while this class's member injection is still in progress.**
     */
    @Inject
    internal lateinit var refreshScheduler: Lazy<RefreshScheduler>

    /**
     * Same [Lazy] requirement as [refreshScheduler] and for the identical reason: resolving
     * [GeoRefreshScheduler] pulls in its own `WorkManager` binding, which must not run before
     * [onCreate] has finished member injection.
     *
     * Review round 2, Critical 2: [GeoRefreshScheduler.reschedule] has exactly one caller in
     * production — [art.yniyniyni.subspace.sync.GeoRefreshWorker]'s own `finally`. Without this
     * field and the call in [onCreate] below, the daily `geo-refresh` periodic job never exists on
     * a fresh install (`enqueueUniquePeriodicWork` is never reached by anything), so the worker
     * that would re-create it never runs either — the whole chain is dead before it starts, and
     * silently: nothing throws, nothing logs, no geo database ever refreshes. [RefreshScheduler]
     * avoids this because [onCreate] bootstraps it the same way; [GeoRefreshScheduler] needs the
     * same bootstrap, not a self-sustaining chain of its own.
     */
    @Inject
    internal lateinit var geoRefreshScheduler: Lazy<GeoRefreshScheduler>

    // Owned by this Application instance, not GlobalScope (§12) — it lives exactly as long as the
    // process does, which is what the launch-time refresh below needs.
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkerFactory).build()

    /**
     * Runs in **every** process, and strictly before Hilt's generated `onCreate`
     * performs member injection — which is exactly what
     * [installGeoAssetPath] requires. See its KDoc before moving this.
     *
     * Unlike the refresh scheduling in [onCreate], this is deliberately *not*
     * gated on [isMainProcess]: `:bg` is the process that actually runs the core,
     * so it is the one that must have the variable set. Setting it in `:main` too
     * is harmless and keeps the two processes identical.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        installGeoAssetPath(this)
    }

    override fun onCreate() {
        super.onCreate()

        // Only in :main (see isMainProcess's KDoc) — :bg is TunnelService's process and has no
        // business refreshing subscriptions or touching WorkManager.
        if (isMainProcess()) {
            // Resolved here, after super.onCreate() completed member injection — see the field's
            // own KDoc. Captured once so the two call sites cannot disagree about which instance
            // they are talking to.
            val scheduler = refreshScheduler.get()
            applicationScope.launch { scheduler.rescheduleOnChanges() }
            registerActivityLifecycleCallbacks(ForegroundCallbacks { onMovedToForeground(scheduler) })

            // Bootstraps the geo-refresh periodic job — see geoRefreshScheduler's KDoc. Unlike
            // the subscription scheduler above, geo refresh has no Room-observable schedule input
            // to react to (the cadence is a fixed daily period; the 7-day freshness cap lives
            // inside GeoAssetRepository.isDueForRefresh, not in the schedule) and no separate
            // on-open trigger, so a single reschedule() call is this scheduler's entire bootstrap.
            applicationScope.launch { geoRefreshScheduler.get().reschedule() }
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
    private fun onMovedToForeground(scheduler: RefreshScheduler) {
        applicationScope.launch {
            try {
                scheduler.refreshDue(onOpen = true)
            } finally {
                scheduler.reschedule()
            }
        }
    }
}

/**
 * Calls [onForeground] each time the app becomes visible, and not once per activity.
 *
 * Counting started activities distinguishes "the user opened the app" from "one screen handed off
 * to the next" — in an A -> B navigation `B.onStart` precedes `A.onStop`, so the count never
 * reaches zero. **A configuration change is not that shape**, and an earlier version of this class
 * claimed it was. Android tears the activity down and rebuilds it strictly sequentially —
 * `onPause -> onStop -> onDestroy -> onCreate -> onStart -> onResume` — with no overlap, so the
 * count really does dip to zero and come back. This app makes that the *only* case that matters:
 * `AndroidManifest.xml` declares exactly one activity and no `android:configChanges`, so there is
 * never a second activity to hold the count up, and a rotation, a light/dark switch, a font-size
 * or locale change, or entering split-screen each fired a full refresh and an
 * `enqueueUniqueWork(REPLACE)`.
 *
 * [Activity.isChangingConfigurations] is what tells the two apart: it is true during the `onStop`
 * of an activity the framework is about to recreate. Such a stop is remembered and consumed by the
 * matching `onStart`, which therefore reports no foreground event.
 *
 * `androidx.lifecycle:lifecycle-process` offers `ProcessLifecycleOwner` for this, and is still not
 * used — a new dependency (§10.7 justification, THIRD_PARTY.md entry) for one callback the
 * platform already provides. Its 700 ms `ON_START` debounce exists to bridge exactly this
 * destroy/recreate gap, which is why dropping the dependency meant having to handle the gap here
 * rather than being free to ignore it.
 */
private class ForegroundCallbacks(
    onForeground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {
    private val tracker = ForegroundTracker(onForeground)

    override fun onActivityStarted(activity: Activity) = tracker.started()

    override fun onActivityStopped(activity: Activity) =
        tracker.stopped(isChangingConfigurations = activity.isChangingConfigurations)

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
