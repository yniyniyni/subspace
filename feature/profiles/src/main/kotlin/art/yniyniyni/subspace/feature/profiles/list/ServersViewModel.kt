// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.parser.directive.UserInfo
import art.yniyniyni.subspace.core.parser.directive.parseUserInfo
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import art.yniyniyni.subspace.feature.profiles.add.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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

        // Task 14: per-group subscription context (quota, last-fetch time, the
        // id `onUpdateSubscription` needs). Which groups are SUBSCRIPTION-sourced
        // is never asked directly — a group's presence in this map (keyed by
        // groupId, one entry per stored subscription) is itself the answer, so
        // a MANUAL group's absence from it is what keeps GroupCard's quota/
        // update/age params null for that group, with no separate "is this a
        // subscription group" flag to keep in sync.
        val subscriptionContextByGroupId =
            profileSource.observeSubscriptions().flatMapLatest { it.observeSubscriptionContextByGroupId() }

        combine(
            allGroups,
            filteredGroups,
            filters,
            profileSource.activeProfileId,
            subscriptionContextByGroupId,
        ) { raw, filtered, f, activeId, context -> buildState(raw, filtered, f, activeId, context) }
            // buildState knows nothing about updateResult, and a sync writes to the very tables
            // this flow observes — so assigning its output wholesale would erase the message on
            // the re-emission the sync itself triggers, which is exactly when it must be visible.
            .onEach { built -> _state.value = built.copy(updateResult = _state.value.updateResult) }
            .launchIn(viewModelScope)
    }

    /**
     * What a `SUBSCRIPTION` group's [ServersGroup] fields are built from: the
     * subscription's own id (fix round, Important 1 — [onUpdateSubscription]'s
     * target), its parsed `subscription-userinfo` (the quota bar), and its
     * [StoredSubscription.lastFetchedAt] (fix round, Important 2 — the age
     * `GroupCard` renders). Everything [StoredSubscription] carries that a
     * group's card can show lives here, in one place, precisely so nothing
     * gets read off [StoredSubscription] in [observeSubscriptionContextByGroupId]
     * and then silently dropped before reaching [ServersGroup] the way
     * [lastFetchedAtEpochMillis] originally was.
     */
    private data class SubscriptionContext(
        val subscriptionId: Long,
        val userInfo: UserInfo?,
        val lastFetchedAtEpochMillis: Long?,
    )

    /**
     * Re-parses `subscription-userinfo` for every stored subscription
     * whenever any of them changes, keyed by the group each one owns.
     *
     * Receiver, not a parameter: keeps the call site above
     * (`profileSource.observeSubscriptions().flatMapLatest { ... }`) reading
     * left-to-right as "subscriptions, then their context", matching this
     * class's other `flatMapLatest` chain immediately above it.
     */
    private fun List<StoredSubscription>.observeSubscriptionContextByGroupId(): Flow<Map<Long, SubscriptionContext>> {
        if (isEmpty()) return flowOf(emptyMap())
        val perSubscription =
            map { sub ->
                profileSource.observeUserInfo(sub.id).map { raw ->
                    sub.groupId to SubscriptionContext(sub.id, raw?.let(::parseUserInfo), sub.lastFetchedAt)
                }
            }
        return combine(perSubscription) { pairs -> pairs.toMap() }
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

    /**
     * Runs one sync of the subscription owning a `SUBSCRIPTION` group right now —
     * `GroupCard`'s Update button — and reports the outcome.
     *
     * The result is not optional to surface: [SyncResult][art.yniyniyni.subspace.core.data.sync.SyncResult]
     * carries the HWID-required and device-limit-reached distinction the whole
     * milestone is built around, and this button was discarding it — a failed
     * refresh from the group card was indistinguishable from a successful one.
     * Found on device, not by any test, because nothing asserted on a failing
     * sync here.
     */
    fun onUpdateSubscription(id: Long) {
        viewModelScope.launch {
            val result = profileSource.syncSubscription(id)
            _state.value = _state.value.copy(updateResult = result.toUserMessage())
        }
    }

    /** Clears [ServersState.updateResult] once the user has read it. */
    fun onDismissUpdateResult() {
        _state.value = _state.value.copy(updateResult = null)
    }

    private data class Filters(val query: String, val protocol: String, val sort: SortOrder)

    private fun buildState(
        raw: List<ProfileGroup>,
        filtered: List<ProfileGroup>,
        filters: Filters,
        activeProfileId: Long?,
        subscriptionContextByGroupId: Map<Long, SubscriptionContext>,
    ): ServersState {
        val totalCountById = raw.associate { it.id to it.profiles.size }
        val groups =
            filtered.map { group ->
                val context = subscriptionContextByGroupId[group.id]
                val quota = context?.userInfo
                ServersGroup(
                    id = group.id,
                    name = group.name,
                    totalProfileCount = totalCountById[group.id] ?: group.profiles.size,
                    profiles = group.profiles.sortedFor(filters.sort, emptyMap()).map { it.toRow(activeProfileId) },
                    // UserInfo.usedBytes defaults an absent upload/download to
                    // zero (its own KDoc); that is correct for "one of the two
                    // was sent" but would draw a fabricated "0 B used" if the
                    // provider sent neither, so that case is excluded here
                    // rather than in the parser — see this task's governing
                    // rule against substituting a zero for an absent value.
                    quotaUsedBytes = quota?.takeIf { it.upload != null || it.download != null }?.usedBytes,
                    quotaTotalBytes = quota?.total,
                    subscriptionId = context?.subscriptionId,
                    lastFetchedAtEpochMillis = context?.lastFetchedAtEpochMillis,
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
        droppedFromSubscriptionAt = droppedFromSubscriptionAt,
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
