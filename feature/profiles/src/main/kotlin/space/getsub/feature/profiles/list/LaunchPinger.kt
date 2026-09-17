// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles.list

import space.getsub.core.data.isDirectiveEnabled
import space.getsub.core.model.ConnectionState

/**
 * Decides whether a group measures itself when the server list is first shown.
 *
 * **This is ours, not a directive reader.** `ping_on_launch` owns it and is on by
 * default, so every group — including a `MANUAL` one with no provider at all —
 * gets a measured latency at launch. Reading the feature off
 * `subscription-ping-onopen-enabled` instead would have left manual groups, and
 * every subscription whose provider omits that header, permanently unsorted;
 * the directive only overrides the decision for its own group (§A.1's scoping).
 *
 * Once-per-session comes from [LatencyTester.claimLaunchRun], which is backed by
 * a cache that dies with `:main`. That is what makes this "once per app start"
 * rather than "once per navigation to the list", with no timestamp to keep and
 * nothing to reset.
 */
internal class LaunchPinger(
    private val tester: LatencyTester,
    private val isMetered: () -> Boolean,
    private val connectionState: () -> ConnectionState,
) {
    /**
     * @param providerValue this group's `subscription-ping-onopen-enabled`, or
     *   null for a manual group or a provider that never sent it.
     */
    // One early return per gate is the point, not a smell: each names a distinct
    // reason not to run, and folding them into a single boolean expression would
    // lose which one applied — the same judgement TunnelService.startCore records
    // for the start sequence.
    @Suppress("ReturnCount")
    fun shouldRun(
        groupId: Long,
        enabledGlobally: Boolean,
        allowMetered: Boolean,
        providerValue: String?,
    ): Boolean {
        // Every gate below is checked *before* the claim, deliberately. A gate is
        // a "not yet", not a "never": claiming here would spend the group's one
        // launch run on a moment we chose to skip, and it would then never run
        // this session even once the condition cleared.
        val state = connectionState()
        // Four concurrent measurements competing with the start sequence is
        // §5.3's territory. Settled Connected and settled Disconnected both pass.
        //
        // Exhaustive with no `else`: M8 added Reconnecting, and an `is`-chain
        // absorbed it silently — a reconnect is a start sequence pending or in
        // flight, so navigating here mid-reconnect could launch a proxy-head
        // burst (one Xray instance per server) against the retrying core. Spec
        // §2.2 chose this shape for FailureReason.retryability for the same
        // reason: a state added later must not fall through whichever branch
        // happens to catch it.
        val startSequenceBusy =
            when (state) {
                is ConnectionState.Connecting,
                ConnectionState.Disconnecting,
                is ConnectionState.Reconnecting,
                -> true

                is ConnectionState.Connected,
                ConnectionState.Disconnected,
                is ConnectionState.Failed,
                -> false
            }
        if (startSequenceBusy) return false

        // §A.1's boolean rule, through the shared predicate: only `true` or `1`
        // enables, and any other value — including blank — disables. A provider
        // that sent nothing leaves our own setting in charge.
        val enabled = providerValue?.let { isDirectiveEnabled(it) } ?: enabledGlobally
        if (!enabled) return false

        // A large list measured with proxy-head starts one Xray instance per
        // server. Doing that unprompted on cellular is real data and real
        // battery; the manual test action stays available regardless.
        if (isMetered() && !allowMetered) return false

        return tester.claimLaunchRun(groupId)
    }
}
