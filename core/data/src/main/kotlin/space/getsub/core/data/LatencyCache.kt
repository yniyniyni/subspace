// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import space.getsub.core.model.LatencyResult
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This session's latency measurements.
 *
 * **Deliberately not Room.** §3 routes state through Room when the *other*
 * process must read it, and nothing in `:bg` consumes a latency —
 * `subscription-autoconnect-type: lowestdelay` is `Consumer.Release`. Keeping these
 * in memory makes session scoping a property of where the data lives rather than
 * a cleanup step someone can forget, and avoids a migration for values that stop
 * being true the moment the network changes.
 *
 * A `@Singleton` is safe here for the same reason `TunnelClient`'s is: §3's
 * warning is about expecting `:bg` to see a singleton. This one is explicitly
 * `:main`-only, and `:bg` reaching for it would be the bug rather than the fix.
 *
 * Consequence to accept: a result survives `:bg` dying but not `:main` dying.
 * That is the right way round — the UI that renders it is what went away.
 */
@Singleton
public class LatencyCache
@Inject
constructor() {
    private val _results = MutableStateFlow<Map<Long, LatencyResult>>(emptyMap())

    /**
     * Measured results by profile id.
     *
     * Absence means "never measured" and must render as an em-dash. It is never a
     * zero — see [LatencyResult] for why a substituted number here is the §10.1
     * failure this whole milestone is careful about.
     */
    public val results: StateFlow<Map<Long, LatencyResult>> = _results.asStateFlow()

    private val _testing = MutableStateFlow<Set<Long>>(emptySet())

    /** Profile ids with a measurement in flight. */
    public val testing: StateFlow<Set<Long>> = _testing.asStateFlow()

    /**
     * Synchronised because [claimLaunchRun] is reachable from any collector of the
     * group list, and its whole contract is that exactly one caller wins.
     */
    private val launchRunClaimed: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    public fun markTesting(profileIds: Collection<Long>) {
        _testing.update { current -> current + profileIds }
    }

    /** Records one measurement and clears that row's in-flight flag. */
    public fun put(
        profileId: Long,
        result: LatencyResult,
    ) {
        _results.update { current -> current + (profileId to result) }
        _testing.update { current -> current - profileId }
    }

    /**
     * Ends a run: any row still marked testing goes back to idle.
     *
     * It deliberately does **not** write a result for those rows. A cancelled or
     * superseded measurement produced no number, and recording one — even a
     * failure — would claim a test happened that did not.
     */
    public fun finish(profileIds: Collection<Long>) {
        _testing.update { current -> current - profileIds.toSet() }
    }

    /**
     * True the first time it is called for [groupId] in this process.
     *
     * This is what makes ping-on-launch fire once per app start rather than once
     * per navigation to the list. Because this object dies with `:main`, "not yet
     * claimed" is true exactly once per launch — no timestamp, no TTL, and
     * nothing to reset.
     */
    public fun claimLaunchRun(groupId: Long): Boolean = launchRunClaimed.add(groupId)

    /**
     * Hands a claim back when the run it was taken for never started.
     *
     * `TunnelClient` drops a run outright when nothing is bound yet, and binding
     * is asynchronous across a process fork — so a list that composes before
     * `onServiceConnected` lands would otherwise burn that group's single launch
     * run on a measurement that never happened. `LaunchPinger` treats every gate
     * as "not yet, not never"; this keeps the drop path honest to the same rule.
     */
    public fun releaseLaunchRun(groupIds: Collection<Long>) {
        launchRunClaimed.removeAll(groupIds.toSet())
    }
}
