// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.Profile
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
}
