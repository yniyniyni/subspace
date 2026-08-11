// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.PingMode
import art.yniyniyni.subspace.core.model.pingModeFrom
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
// One handler per user action on a screen that carries search, protocol filter,
// screen-level sort, per-group sort, group rename/delete, subscription update,
// and M4.5's three measurement actions. Collapsing any of them into a shared
// "onEvent(Action)" would trade a legible call site for an enum — the same
// judgement ProfileRepository and ProfileSource already record.
@Suppress("TooManyFunctions")
internal class ServersViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
    private val latencyTester: LatencyTester,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val protocolFilter = MutableStateFlow(ALL_PROTOCOLS_SENTINEL)
    private val sort = MutableStateFlow(SortOrder.AsListed)

    /**
     * The user's own order for a specific group, which outranks that group's
     * provider directive and the screen default alike (§A.1's precedence, made
     * visible by [ServersGroup.sortFromProvider]).
     */
    private val groupSortOverrides = MutableStateFlow<Map<Long, SortOrder>>(emptyMap())

    private val _state = MutableStateFlow(ServersState())
    val state: StateFlow<ServersState> = _state.asStateFlow()

    /**
     * Each group's `subscription-ping-onopen-enabled`, refreshed whenever state
     * is rebuilt.
     *
     * Kept beside the state rather than inside it because it is not something the
     * screen renders — it only decides whether [onServersShown] measures a group.
     * A `MANUAL` group is simply absent, which leaves our own setting in charge.
     */
    private var providerPingOnOpen: Map<Long, String?> = emptyMap()

    /**
     * Each group's `ping-type`, refreshed whenever state is rebuilt. Absent means
     * that group follows the user's global mode setting.
     */
    private var providerPingType: Map<Long, String?> = emptyMap()

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

        // Folded into one upstream because the outer combine is already at the
        // typed overload's five-argument limit. These four change together from
        // the screen's point of view — they are all "how the rows are presented",
        // as opposed to what the rows are.
        val presentation =
            combine(
                filters,
                groupSortOverrides,
                latencyTester.results,
                latencyTester.testing,
                ::Presentation,
            )

        combine(
            allGroups,
            filteredGroups,
            presentation,
            profileSource.activeProfileId,
            subscriptionContextByGroupId,
        ) { raw, filtered, p, activeId, context -> buildState(raw, filtered, p, activeId, context) }
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
        /**
         * This subscription's `subscriptions-sort-type`, or null when its
         * provider sent none. Resolved here, per subscription, because §A.1
         * scopes a directive to the subscription that delivered it — reading it
         * once into a screen-wide value would let one provider reorder another's
         * rows.
         */
        val sortType: String?,
        /** This subscription's `subscription-ping-onopen-enabled`, or null when unset. */
        val pingOnOpen: String?,
        /**
         * This subscription's `ping-type`, or null when its provider sent none.
         *
         * Per subscription for the same §A.1 reason [sortType] is: a provider
         * choosing `tcp` must not change how a *different* provider's servers are
         * measured, or the two groups' numbers stop being comparable while looking
         * identical on screen.
         */
        val pingType: String?,
    )

    /** How the rows are presented, as opposed to what the rows are. */
    private data class Presentation(
        val filters: Filters,
        val groupSortOverrides: Map<Long, SortOrder>,
        val latencies: Map<Long, LatencyResult>,
        val testing: Set<Long>,
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
                combine(
                    profileSource.observeUserInfo(sub.id),
                    profileSource.observeEffective(sub.id, KEY_SUBSCRIPTIONS_SORT_TYPE, default = null),
                    profileSource.observeEffective(sub.id, KEY_PING_ON_OPEN, default = null),
                    profileSource.observeEffective(sub.id, KEY_PING_TYPE, default = null),
                ) { raw, sortType, pingOnOpen, pingType ->
                    sub.groupId to
                        SubscriptionContext(
                            subscriptionId = sub.id,
                            userInfo = raw?.let(::parseUserInfo),
                            lastFetchedAtEpochMillis = sub.lastFetchedAt,
                            sortType = sortType.value,
                            pingOnOpen = pingOnOpen.value,
                            pingType = pingType.value,
                        )
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

    /** Measures one server. A one-element run — the same path a group run takes. */
    fun onTestProfile(profileId: Long) {
        val group = _state.value.groups.firstOrNull { g -> g.profiles.any { it.id == profileId } }
        val modes = group?.let { modesFor(listOf(it)) }.orEmpty()
        viewModelScope.launch { latencyTester.test(listOf(profileId), modes) }
    }

    /**
     * Measures every row currently visible in [groupId].
     *
     * Visible, not every stored row: measuring what a search has filtered out
     * would spend a minute of the user's battery on servers they cannot see.
     */
    fun onTestGroup(groupId: Long) {
        val group = _state.value.groups.firstOrNull { it.id == groupId }
        val ids = group?.profiles?.map { it.id }.orEmpty()
        val modes = group?.let { modesFor(listOf(it)) }.orEmpty()
        viewModelScope.launch { latencyTester.test(ids, modes) }
    }

    /**
     * Stops scheduling.
     *
     * In-flight measurements finish in `:bg` and their results are discarded by
     * run id; the affected rows go back to idle rather than to an invented
     * result.
     */
    fun onCancelTests() {
        latencyTester.cancel()
    }

    private val launchPinger =
        LaunchPinger(
            tester = latencyTester,
            isMetered = { latencyTester.isMetered() },
            connectionState = { latencyTester.connectionState() },
        )

    /**
     * Called by the screen once it has groups to show.
     *
     * Every group is offered on every call; [LaunchPinger] decides, and its
     * once-per-session claim is what makes calling this on each recomposition
     * safe. Tied to first view of the list rather than to process start so that a
     * user who launches into Home and connects never pays for measurements they
     * did not look at, and so `:bg` is not started for a run nobody will see.
     */
    fun onServersShown() {
        viewModelScope.launch {
            val enabled = latencyTester.pingOnLaunch.first()
            val allowMetered = latencyTester.pingOnLaunchMetered.first()
            // Collected into **one** run rather than one per group. Starting a run
            // supersedes any run in flight, so a loop of per-group starts left only
            // the last group actually measured — invisible with a single group, and
            // silently wrong with two. Found by review, not by the device run.
            val eligible =
                _state.value.groups.filter { group ->
                    launchPinger.shouldRun(group.id, enabled, allowMetered, providerPingOnOpen[group.id])
                }
            if (eligible.isEmpty()) return@launch
            val ids = eligible.flatMap { group -> group.profiles.map { it.id } }
            latencyTester.test(ids, modesFor(eligible))
        }
    }

    /**
     * Each profile's measurement mode, from the `ping-type` its own group's
     * provider set. Absent leaves that profile on the user's global setting.
     */
    private fun modesFor(groups: List<ServersGroup>): Map<Long, PingMode> =
        groups
            .flatMap { group ->
                val mode = providerPingType[group.id]?.let(::pingModeFrom)
                if (mode == null) emptyList() else group.profiles.map { row -> row.id to mode }
            }.toMap()

    /**
     * The user's own order for one group, outranking that group's provider.
     *
     * Per group rather than per screen: §A.1 scopes `subscriptions-sort-type` to
     * the subscription that delivered it, so overriding it has to be scoped the
     * same way.
     */
    fun onGroupSortChanged(
        groupId: Long,
        order: SortOrder,
    ) {
        groupSortOverrides.value = groupSortOverrides.value + (groupId to order)
    }

    private data class Filters(val query: String, val protocol: String, val sort: SortOrder)

    private fun buildState(
        raw: List<ProfileGroup>,
        filtered: List<ProfileGroup>,
        presentation: Presentation,
        activeProfileId: Long?,
        subscriptionContextByGroupId: Map<Long, SubscriptionContext>,
    ): ServersState {
        val filters = presentation.filters
        val totalCountById = raw.associate { it.id to it.profiles.size }
        providerPingOnOpen =
            subscriptionContextByGroupId.mapValues { (_, context) -> context.pingOnOpen }
        providerPingType =
            subscriptionContextByGroupId.mapValues { (_, context) -> context.pingType }
        val groups =
            filtered.map { group ->
                val context = subscriptionContextByGroupId[group.id]
                val quota = context?.userInfo
                val (effectiveSort, fromProvider) =
                    resolveSort(
                        override = presentation.groupSortOverrides[group.id],
                        providerValue = context?.sortType,
                        default = filters.sort,
                    )
                val rows =
                    group.profiles
                        .sortedFor(effectiveSort, presentation.latencies)
                        .map { it.toRow(activeProfileId, presentation.latencies, presentation.testing) }
                ServersGroup(
                    id = group.id,
                    name = group.name,
                    totalProfileCount = totalCountById[group.id] ?: group.profiles.size,
                    profiles = rows,
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
                    sort = effectiveSort,
                    sortFromProvider = fromProvider,
                )
            }
        return ServersState(
            groups = groups,
            query = filters.query,
            protocolFilter = filters.protocol,
            defaultSort = filters.sort,
            availableProtocols = raw.availableProtocolLabels(),
        )
    }

    /**
     * §A.1's precedence for one group's order: **the user's own choice, else the
     * provider's, else the screen default.**
     *
     * The second component is what [ServersGroup.sortFromProvider] renders. §A.1
     * requires provider-versus-user precedence to be visible, so a group the
     * provider is ordering says so, and a user override clears the marker.
     */
    private fun resolveSort(
        override: SortOrder?,
        providerValue: String?,
        default: SortOrder,
    ): Pair<SortOrder, Boolean> {
        val fromProvider = sortOrderFromDirective(providerValue)
        return when {
            override != null -> override to false
            fromProvider != null -> fromProvider to true
            else -> default to false
        }
    }
}

/** `DirectiveRegistry` keys this screen resolves per subscription. */
private const val KEY_SUBSCRIPTIONS_SORT_TYPE = "subscriptions-sort-type"
private const val KEY_PING_ON_OPEN = "subscription-ping-onopen-enabled"
private const val KEY_PING_TYPE = "ping-type"

private fun StoredProfile.toRow(
    activeProfileId: Long?,
    latencies: Map<Long, LatencyResult>,
    testing: Set<Long>,
): ServerRow =
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
        // Absent stays absent: an unmeasured row renders an em-dash, never a zero.
        latency = latencies[id],
        isTesting = id in testing,
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
