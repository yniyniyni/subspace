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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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
            tunnel.latencies,
            tunnel.measuring,
        ) { connection, activeProfile, hasAnyProfile, latencies, measuring ->
            HomeState(
                connection = connection,
                activeProfile = activeProfile,
                hasAnyProfile = hasAnyProfile,
                // Keyed on the active profile: switching servers must not carry
                // the previous one's number across, which would be the wrong
                // measurement rather than merely a stale one.
                latency = activeProfile?.let { latencies[it.id] },
                isMeasuringLatency = activeProfile != null && activeProfile.id in measuring,
            )
        }.onEach { _state.value = it }
            .launchIn(viewModelScope)
    }

    /**
     * Called when Home is shown.
     *
     * Home is the landing screen, and before this it sat at an em-dash until the
     * user visited Servers and came back — because ping-on-launch fired only from
     * that screen's list. Populated-or-not depending on which tab you happened to
     * open is worse than either, so Home joins the same launch run.
     *
     * Deliberately measures only when this profile has no reading yet: that makes
     * it once per session without a second claim to keep in step with
     * `LatencyCache`'s, and re-running on every recomposition is therefore free.
     *
     * No metered gate here, unlike the group runs. That gate exists because
     * measuring forty servers on cellular is real data; one server is a single
     * connect, and skipping it would recreate the empty-Home inconsistency this
     * exists to remove.
     */
    fun onHomeShown() {
        viewModelScope.launch {
            if (!tunnel.pingOnLaunch.first()) return@launch
            val profileId = _state.value.activeProfile?.id ?: return@launch
            if (profileId in tunnel.latencies.value || profileId in tunnel.measuring.value) return@launch
            tunnel.measure(profileId)
        }
    }

    /**
     * Measures the active profile.
     *
     * A no-op with nothing selected — there is no server to measure, and the tile
     * offers no affordance in that state either.
     */
    fun onTestLatency() {
        val profileId = _state.value.activeProfile?.id ?: return
        viewModelScope.launch { tunnel.measure(profileId) }
    }

    /**
     * Called once VPN consent has been granted.
     *
     * Consent is the Activity's job — a ViewModel cannot launch an intent for
     * a result — so the screen asks first and calls this only on approval.
     *
     * A no-op when [HomeState.canConnect] is `false`: nothing is selected, the selected row's
     * config failed to decode, its transport is one `:core:xray` cannot emit, or a session is
     * already in flight. There is nothing sensible to connect to, so this silently refuses
     * rather than asking the service to attempt it — the same "refuse rather than guess" rule
     * [art.yniyniyni.subspace.service.ProfileParcel.toProfile] applies to an undecodable
     * parcel.
     *
     * That precondition is **re-read here**, not assumed from when consent was requested (PR
     * #4 review, P1 finding B). The system consent dialog is asynchronous and this process
     * keeps running behind it, so between the request and the approval the tunnel state can
     * change and the active row can be edited, deselected or deleted. Enforcing it only at
     * the tap site let a stale callback stack a second connection or dial a profile that had
     * since become unconnectable.
     *
     * One snapshot, read once: checking [HomeState.canConnect] on one read of [state] and
     * then resolving the profile from another would reintroduce the same race inside this
     * function, which is why [HomeState.activeProfile] is taken from the value that was
     * checked rather than re-read.
     */
    fun onConsentGranted() {
        val snapshot = state.value
        if (!snapshot.canConnect) return
        // Both non-null by canConnect: it is false for a null activeProfile and false for a
        // row whose outbound failed to decode (StoredProfile.connectable). Resolved with
        // `let` rather than two more early returns so the precondition stays the single gate
        // above — a second `?: return` here would read as a case this function handles, when
        // it is actually unreachable.
        val activeProfile = snapshot.activeProfile
        activeProfile?.toProfile()?.let { profile -> tunnel.connect(profile, rowId = activeProfile.id) }
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
