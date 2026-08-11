// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The tunnel, as this screen needs to see it.
 *
 * `:app` supplies the implementation, which binds `TunnelService` over AIDL.
 * The interface exists so the ViewModel can be reasoned about — and tested —
 * without a live binder, and so `:feature:home` does not reach across the
 * process boundary itself.
 *
 * ARCHITECTURE.md §5.5: [state] is a *cache* of what the service reported. It is
 * never the source. Any implementation must re-read the real state on bind,
 * because after process death the UI's idea of the world is worthless.
 */
internal interface TunnelConnection {
    val state: StateFlow<ConnectionState>

    /**
     * @param rowId the Room primary key of the profile being connected —
     *   see [art.yniyniyni.subspace.service.TunnelClient.connect]'s KDoc for
     *   why this is required rather than defaulted.
     */
    fun connect(
        profile: Profile,
        rowId: Long,
    )

    fun disconnect()

    /**
     * This session's measurements, keyed by profile id.
     *
     * Absence means never measured, and renders an em-dash — never `0 ms`.
     *
     * Home reads the same session-scoped store the Servers list writes to, so a
     * server measured there already shows a number here. `:feature:*` modules
     * cannot depend on each other (§4), which is why this arrives through this
     * seam rather than through the Servers screen's own.
     */
    val latencies: StateFlow<Map<Long, LatencyResult>>

    /** Profile ids with a measurement in flight. */
    val measuring: StateFlow<Set<Long>>

    /** The user's ping-on-launch setting, so Home can join that run rather than sit empty. */
    val pingOnLaunch: Flow<Boolean>

    /**
     * Measures one profile — a one-element run, the same path the Servers list
     * takes.
     *
     * Called by the tile's tap, and once per session by [HomeViewModel.onHomeShown]
     * when ping-on-launch is on. Nothing else: M4.5 deliberately adds no
     * measurement to the connect path and no periodic re-measure, so the tunnel
     * start sequence is untouched by this milestone and no timer wakes the device
     * to spin up an Xray instance while the screen is off.
     */
    suspend fun measure(profileId: Long)
}
