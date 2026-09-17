// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// File groups the teardown order's type and its one pure function together,
// the same shape as BootDecision.kt — see TunnelService.stopTunnel, which is
// what actually consumes the ordering rule this file names.
@file:Suppress("MatchingDeclarationName", "Filename")

package space.getsub.service.log

/**
 * The teardown steps whose *relative order* is load-bearing.
 *
 * Not a scheduler and not a state machine — `TunnelService.stopTunnel` still
 * performs the work. This exists so the one ordering rule that cannot be seen
 * by reading `stopTunnel` top to bottom has a test, without adding Robolectric
 * to reach a `VpnService` (ARCHITECTURE.md §10.7). Spec §6 item #9 prescribes
 * exactly this move for the P1 ordering half; this is its first application.
 */
internal enum class TeardownStep {
    StopCore,
    StopTun2Socks,
    CloseTun,
    ClearNotification,

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
        TeardownStep.StopCore,
        TeardownStep.StopTun2Socks,
        TeardownStep.CloseTun,
        TeardownStep.ClearNotification,
        TeardownStep.StopLogCapture,
    )
