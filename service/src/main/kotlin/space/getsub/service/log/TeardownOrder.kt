// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// File groups the teardown order's type and its one pure function together,
// the same shape as BootDecision.kt — see TunnelService.stopTunnel, which is
// what actually consumes the ordering rule this file names.
@file:Suppress("MatchingDeclarationName", "Filename")

package space.getsub.service.log

/**
 * `TunnelService.stopTunnel`'s teardown steps, in the order that function
 * actually executes them as of this commit — not a scheduler and not a state
 * machine; `stopTunnel` still performs the work, and a future change to that
 * function's body is what could make this list stale.
 *
 * **What is actually tested, and what is not.** [teardownOrder]'s ordering
 * test constrains exactly one thing: [TeardownStep.StopLogCapture] is last,
 * after every phase that logs. It does **not** constrain the relative order
 * of [StopTun2Socks], [CloseTun] and [StopCore] against each other — read
 * `LogCaptureLifecycleTest` before trusting this list for anything beyond
 * that. The list is written to mirror `stopTunnel`'s real sequence anyway,
 * because a teardown-ordering type that misdescribes the teardown it names is
 * exactly the trap `CLAUDE.md`'s citation-hazard note records: a reader who
 * trusts the order here over the source it claims to describe.
 *
 * Exists so the one ordering rule that *is* tested cannot be seen by reading
 * `stopTunnel` top to bottom without adding Robolectric to reach a
 * `VpnService` (ARCHITECTURE.md §10.7). Spec §6 item #9 prescribes exactly
 * this move for the P1 ordering half; this is its first application.
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
