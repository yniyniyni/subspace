// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub

/**
 * Turns a stream of activity start/stop events into "the app became visible" events.
 *
 * Split out of `SubspaceApplication`'s `ActivityLifecycleCallbacks` so the counting rule can be
 * tested on the JVM without an `Activity`. That is not incidental tidying: the rule shipped wrong
 * once already, on a premise that read as obviously true and was not, and the class of bug is
 * invisible to a test that cannot drive the sequences below.
 *
 * The rule, and why each half exists:
 *
 * - **A -> B navigation does not count as leaving.** `B.onStart` precedes `A.onStop`, so the
 *   started count never reaches zero and no event fires. Straightforward, and what the counter is
 *   nominally for.
 * - **A configuration change does not count as leaving either, and this is the hard half.** The
 *   framework tears the activity down and rebuilds it strictly sequentially —
 *   `onPause -> onStop -> onDestroy -> onCreate -> onStart -> onResume` — with no overlap, so the
 *   count genuinely dips to zero and comes back. Counting alone cannot tell that apart from a real
 *   departure and return. [stopped]'s `isChangingConfigurations` flag can: it is true during the
 *   `onStop` of an activity the framework is about to recreate, so that stop is remembered and
 *   consumed by the matching [started], which then reports nothing.
 *
 * The app this serves declares exactly one activity and no `android:configChanges`, which makes
 * the configuration change the *only* case that ever moves the count — there is no second activity
 * to hold it up — so getting that half wrong meant every rotation, theme switch, font-size change,
 * locale change and split-screen entry fired a full subscription refresh.
 */
internal class ForegroundTracker(
    private val onForeground: () -> Unit,
) {
    private var startedActivities = 0
    private var pendingRecreations = 0

    fun started() {
        if (pendingRecreations > 0) {
            pendingRecreations--
            startedActivities++
            return
        }
        if (startedActivities++ == 0) onForeground()
    }

    /**
     * @param isChangingConfigurations `Activity.isChangingConfigurations` — true when the framework
     *   is stopping this activity only to rebuild it immediately.
     */
    fun stopped(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) pendingRecreations++
        // Guarded rather than a bare decrement: callbacks are registered in Application.onCreate,
        // which always precedes any activity in the process, so an unmatched stop should be
        // impossible — but a counter that can go negative would silently stop firing forever, and
        // that is too quiet a failure to leave to an argument about what cannot happen.
        if (startedActivities > 0) startedActivities--
    }
}
