// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class ServersViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val protocolFilter = MutableStateFlow(ALL_PROTOCOLS_SENTINEL)
    private val sort = MutableStateFlow(SortOrder.AsListed)

    private val _state = MutableStateFlow(ServersState())
    val state: StateFlow<ServersState> = _state.asStateFlow()

    init {
        // Unfiltered: the source availableProtocols is derived from (every
        // protocol actually stored, not a hardcoded list) and the truth
        // GroupCard's header/delete-warning count reads, independent of
        // whatever the user is currently searching for.
        val allGroups = profileSource.observeGroups(query = "", protocol = null)

        // The filter/sort trio is re-read on every combine tick so ServersState
        // always mirrors the user's current selection, even before the
        // filtered query below re-resolves.
        val filters = combine(query, protocolFilter, sort, ::Filters)

        // Search and protocol filtering happen in SQL, over ProfileEntity's
        // shadow columns (ProfileRepository.observeGroups) — flatMapLatest
        // re-subscribes to a fresh filtered query every time query/protocol
        // change, cancelling the previous one rather than stacking
        // subscriptions.
        val filteredGroups =
            filters.flatMapLatest { f -> profileSource.observeGroups(f.query, f.protocol.toRawProtocolOrNull()) }

        combine(
            allGroups,
            filteredGroups,
            filters,
            profileSource.activeProfileId,
        ) { raw, filtered, f, activeId -> buildState(raw, filtered, f, activeId) }
            .onEach { _state.value = it }
            .launchIn(viewModelScope)
    }

    fun onQueryChanged(text: String) {
        query.value = text
    }

    fun onProtocolFilterChanged(label: String) {
        protocolFilter.value = label
    }

    fun onSortChanged(order: SortOrder) {
        sort.value = order
    }

    /** Picks [id] as the active profile — what Home connects to next. */
    fun onProfileSelected(id: Long) {
        viewModelScope.launch { profileSource.setActiveProfile(id) }
    }

    fun onRenameGroup(
        id: Long,
        name: String,
    ) {
        viewModelScope.launch { profileSource.renameGroup(id, name) }
    }

    /** The FK cascades: every profile in [id] is removed with it. Callers confirm first. */
    fun onDeleteGroup(id: Long) {
        viewModelScope.launch { profileSource.deleteGroup(id) }
    }

    private data class Filters(val query: String, val protocol: String, val sort: SortOrder)

    private fun buildState(
        raw: List<ProfileGroup>,
        filtered: List<ProfileGroup>,
        filters: Filters,
        activeProfileId: Long?,
    ): ServersState {
        val totalCountById = raw.associate { it.id to it.profiles.size }
        val groups =
            filtered.map { group ->
                ServersGroup(
                    id = group.id,
                    name = group.name,
                    totalProfileCount = totalCountById[group.id] ?: group.profiles.size,
                    profiles = group.profiles.sortedFor(filters.sort).map { it.toRow(activeProfileId) },
                )
            }
        return ServersState(
            groups = groups,
            query = filters.query,
            protocolFilter = filters.protocol,
            sort = filters.sort,
            availableProtocols = raw.availableProtocolLabels(),
        )
    }
}

private fun List<StoredProfile>.sortedFor(order: SortOrder): List<StoredProfile> =
    when (order) {
        // Already ORDER BY position, id from the DAO — the user's own arrangement.
        SortOrder.AsListed -> this
        SortOrder.Alphabetical -> sortedBy { it.name.lowercase() }
        // Never-connected rows have no lastConnectedAt; MIN_VALUE sorts them
        // last rather than first among ties, which is a stable sort — so two
        // never-connected rows keep their relative AsListed order.
        SortOrder.LastUsed -> sortedByDescending { it.lastConnectedAt ?: Long.MIN_VALUE }
    }

private fun StoredProfile.toRow(activeProfileId: Long?): ServerRow =
    ServerRow(
        id = id,
        name = name,
        protocol = protocol,
        protocolDisplay = protocol.toProtocolDisplayName(),
        transport = transport,
        kind = kind,
        compatibilityMode = compatibilityMode,
        connectable = connectable,
        isActive = id == activeProfileId,
    )

private fun List<ProfileGroup>.availableProtocolLabels(): List<String> =
    listOf(ALL_PROTOCOLS_SENTINEL) +
        flatMap { it.profiles }.map { it.protocol }.distinct().map { it.toProtocolDisplayName() }

/**
 * The canonical protocol names [art.yniyniyni.subspace.core.data.ProfileRepository]
 * stores, capitalised for display.
 */
private fun String.toProtocolDisplayName(): String =
    when (this) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "socks" -> "SOCKS"
        else -> this
    }

/**
 * The inverse of [toProtocolDisplayName] — every mapping above is a case
 * variant of the stored value with no other characters, so lowercasing a
 * display label always recovers the raw protocol string the DAO stores.
 */
private fun String.toRawProtocolOrNull(): String? = if (this == ALL_PROTOCOLS_SENTINEL) null else lowercase()
