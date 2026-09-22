// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// File groups the teardown order's type and its one pure function together,
// the same shape as BootDecision.kt — see TunnelService.stopTunnel, which
// iterates teardownOrder() and dispatches each TeardownStep through a `when`,
// so this list drives the real sequence rather than describing it from afar.
@file:Suppress("MatchingDeclarationName", "Filename")

package space.getsub.service.log

/**
 * `TunnelService.stopTunnel`'s teardown steps, in the order that function
 * actually executes them.
 *
 * **Consumed, not mirrored (review finding M1/M2).** An earlier revision had
 * `stopTunnel` perform the work inline, with this list asserted against in a
 * test but read by no production code — a reorder inside `stopTunnel` could
 * silently make the list wrong and nothing would catch it, `TeardownOrder`'s
 * own KDoc conceding as much ("a future change... is what could make this
 * list stale"). `stopTunnel` now does the opposite: it calls [teardownOrder]
 * and runs a `when` over each [TeardownStep] in the order returned, so this
 * list is the schedule, not a description of one kept elsewhere. Spec §6
 * item #9 prescribed exactly this move, the same way [BootDecision] and
 * `SessionIntent` extract a decision the caller then actually consumes.
 *
 * **What is actually tested, and what is not.** [teardownOrder]'s ordering
 * test constrains exactly one thing: [TeardownStep.StopLogCapture] is last,
 * after every phase that logs. It does **not** constrain the relative order
 * of [StopTun2Socks], [CloseTun] and [StopCore] against each other — read
 * `LogCaptureLifecycleTest` before trusting this list for anything beyond
 * that.
 *
 * Exists so the one ordering rule that *is* tested cannot be seen by reading
 * `stopTunnel` top to bottom without adding Robolectric to reach a
 * `VpnService` (ARCHITECTURE.md §10.7).
 */
internal enum class TeardownStep {
    /** `Tun2Socks.stop()`. Stops feeding packets in before removing their destination. */
    StopTun2Socks,

    /** `fd?.close()`. */
    CloseTun,

    /** `xray?.stopBlocking()`. */
    StopCore,

    /** `removeForegroundSafely()`. */
    ClearNotification,

    /**
     * `trafficLoop.stop()`. Spec §1.5: stopped after the state that ends the
     * session has published, and before the capture that must outlive it —
     * immediately ahead of [StopLogCapture], not at any other position.
     */
    StopTrafficSampler,

    /**
     * Last, always. Spec §3.3: the capture must outlive every phase that logs,
     * because M8's phase instrumentation (`705f4ab`) is W7's only evidence and a
     * capture that stops first records the teardown right up to the point where
     * it becomes interesting.
     */
    StopLogCapture,
}

internal fun teardownOrder(): List<TeardownStep> =
    listOf(
        TeardownStep.StopTun2Socks,
        TeardownStep.CloseTun,
        TeardownStep.StopCore,
        TeardownStep.ClearNotification,
        TeardownStep.StopTrafficSampler,
        TeardownStep.StopLogCapture,
    )
