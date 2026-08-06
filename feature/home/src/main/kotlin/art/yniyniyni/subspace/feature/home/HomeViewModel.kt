// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
internal class HomeViewModel @Inject constructor(
    private val tunnel: TunnelConnection,
    private val profileSource: ActiveProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    init {
        // §5.5: connection comes from the service and is only ever mirrored
        // here — this combine never derives it from anything local. The
        // active profile is likewise read, never chosen, by this screen:
        // choosing happens on the Servers screen via
        // SettingsRepository.setActiveProfile; this only reflects the
        // result.
        combine(
            tunnel.state,
            profileSource.activeProfile,
            profileSource.hasAnyProfile,
        ) { connection, activeProfile, hasAnyProfile ->
            HomeState(
                connection = connection,
                activeProfile = activeProfile,
                hasAnyProfile = hasAnyProfile,
            )
        }.onEach { _state.value = it }
            .launchIn(viewModelScope)
    }

    /**
     * Called once VPN consent has been granted.
     *
     * Consent is the Activity's job — a ViewModel cannot launch an intent for
     * a result — so the screen asks first and calls this only on approval.
     *
     * A no-op when [HomeState.canConnect] would be `false`: nothing is
     * selected, or the selected row's config failed to decode. There is
     * nothing sensible to connect to, so this silently refuses rather than
     * asking the service to attempt it — the same "refuse rather than guess"
     * rule [art.yniyniyni.subspace.service.ProfileParcel.toProfile] applies
     * to an undecodable parcel.
     */
    fun onConsentGranted() {
        val activeProfile = state.value.activeProfile ?: return
        val profile = activeProfile.toProfile() ?: return
        tunnel.connect(profile, rowId = activeProfile.id)
    }

    fun onDisconnect() {
        tunnel.disconnect()
    }
}

/**
 * Rebuilds the domain [Profile] this row's persisted config decodes to, or
 * `null` for a corrupt row ([StoredProfile.outbound] is only ever null when
 * the stored JSON failed to parse — see its own KDoc).
 *
 * [Profile.id] wants a String and [StoredProfile] only carries a Room
 * [Long] primary key, so this stringifies it. That id is opaque wire
 * plumbing only [art.yniyniyni.subspace.service.ProfileParcel] reads back,
 * never the row identity — the row id travels separately, as
 * [TunnelConnection.connect]'s own `rowId` parameter.
 */
private fun StoredProfile.toProfile(): Profile? =
    outbound?.let { decoded -> Profile(id = id.toString(), name = name, outbound = decoded) }
