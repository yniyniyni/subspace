// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.parser.directive.DirectiveRegistry
import art.yniyniyni.subspace.core.parser.directive.KindResult
import art.yniyniyni.subspace.core.parser.directive.UserInfo
import art.yniyniyni.subspace.core.parser.directive.canonicalise
import art.yniyniyni.subspace.core.parser.directive.parseUserInfo
import art.yniyniyni.subspace.feature.profiles.ProfileSource
import art.yniyniyni.subspace.feature.profiles.R
import art.yniyniyni.subspace.feature.profiles.add.UserMessage
import art.yniyniyni.subspace.feature.profiles.add.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val KEY_INTERVAL = "profile-update-interval"
private const val KEY_TIMEOUT = "subscription-request-timeout"
private const val KEY_AUTO_UPDATE = "subscription-auto-update-enable"
private const val KEY_AUTO_UPDATE_OPEN = "subscription-auto-update-open-enable"
private const val KEY_USER_AGENT = "change-user-agent"

// Mirrors RefreshScheduler.DEFAULT_INTERVAL_HOURS and SubscriptionSyncer.DEFAULT_TIMEOUT_SECONDS
// exactly — duplicated, not shared, for the same reason RefreshScheduler's own copy of
// DirectiveKind.Integer(1, 8760)'s bounds is duplicated rather than imported: `:feature:profiles`
// has no path to either the `:app`-only or :core:data-internal constant for one number, and this
// row must show what the app will actually fall back to, not an invented placeholder.
private const val DEFAULT_INTERVAL_HOURS = "12"
private const val DEFAULT_TIMEOUT_SECONDS = "9"
private const val DEFAULT_AUTO_UPDATE = "true"

private fun canonicalUserAgent(raw: String): String? {
    val canonical =
        DirectiveRegistry.specs[KEY_USER_AGENT]?.kind?.canonicalise(raw)
            as? KindResult.Canonical
            ?: return null
    return canonical.value.takeIf { value -> value.none(Char::isISOControl) }
}

/**
 * `:app`'s `SubscriptionDetail(subscriptionId)` route.
 *
 * Follows [art.yniyniyni.subspace.feature.profiles.editor.EditorViewModel]'s shape: [load] is
 * called once per navigation (from the screen's `LaunchedEffect(subscriptionId)`), not injected
 * via a constructor argument, so this class stays a plain `hiltViewModel()` with no
 * `SavedStateHandle` wiring.
 */
@HiltViewModel
internal class SubscriptionDetailViewModel
@Inject
constructor(
    private val profileSource: ProfileSource,
) : ViewModel() {
    private val _state = MutableStateFlow(SubscriptionDetailState())
    val state: StateFlow<SubscriptionDetailState> = _state.asStateFlow()

    /**
     * Refresh-in-flight/result/delete signals — not derived from [ProfileSource]'s own flows, so
     * kept apart from [buildState]'s inputs and merged back in on every recomposition of them.
     */
    private val ephemeral = MutableStateFlow(Ephemeral())

    private var loadedId: Long? = null
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    fun load(subscriptionId: Long) {
        if (loadedId == subscriptionId) return
        loadedId = subscriptionId
        loadJob?.cancel()
        _state.value = SubscriptionDetailState(subscriptionId = subscriptionId)
        ephemeral.value = Ephemeral()

        val subscriptionFlow =
            profileSource.observeSubscriptions().map { subs ->
                subs.firstOrNull { it.id == subscriptionId }
            }
        val groupFlow = profileSource.observeGroups(query = "", protocol = null)
        val userInfoFlow =
            profileSource.observeUserInfo(subscriptionId).map { raw -> raw?.let(::parseUserInfo) }
        val rowsFlow = observeDirectiveRows(subscriptionId)
        val detailsFlow =
            combine(
                rowsFlow,
                profileSource.observeEffective(subscriptionId, KEY_USER_AGENT, default = null),
            ) { rows, userAgent ->
                DetailSettings(rows = rows, providerUserAgent = userAgent.providerValue)
            }

        loadJob =
            combine(subscriptionFlow, groupFlow, userInfoFlow, detailsFlow, ephemeral) {
                    subscription,
                    groups,
                    userInfo,
                    settings,
                    eph,
                ->
                buildState(
                    DetailInputs(
                        subscriptionId = subscriptionId,
                        subscription = subscription,
                        groups = groups,
                        userInfo = userInfo,
                        rows = settings.rows,
                        providerUserAgent = settings.providerUserAgent,
                        ephemeral = eph,
                    ),
                )
            }.onEach { _state.value = it }.launchIn(viewModelScope)
    }

    private fun observeDirectiveRows(id: Long): Flow<List<DirectiveRow>> =
        combine(
            profileSource.observeEffective(id, KEY_INTERVAL, DEFAULT_INTERVAL_HOURS),
            profileSource.observeEffective(id, KEY_TIMEOUT, DEFAULT_TIMEOUT_SECONDS),
            profileSource.observeEffective(id, KEY_AUTO_UPDATE, DEFAULT_AUTO_UPDATE),
            profileSource.observeEffective(id, KEY_AUTO_UPDATE_OPEN, DEFAULT_AUTO_UPDATE),
        ) { interval, timeout, autoUpdate, autoUpdateOpen ->
            listOf(
                DirectiveRow(
                    DirectiveRowKind.Hours,
                    R.string.subscription_row_interval_label,
                    SettingRowState.from(interval),
                ),
                DirectiveRow(
                    DirectiveRowKind.Seconds,
                    R.string.subscription_row_timeout_label,
                    SettingRowState.from(timeout),
                ),
                DirectiveRow(
                    DirectiveRowKind.Toggle,
                    R.string.subscription_row_auto_update_label,
                    SettingRowState.from(autoUpdate),
                ),
                DirectiveRow(
                    DirectiveRowKind.Toggle,
                    R.string.subscription_row_auto_update_open_label,
                    SettingRowState.from(autoUpdateOpen),
                ),
            )
        }

    private fun buildState(inputs: DetailInputs): SubscriptionDetailState {
        val subscription = inputs.subscription
        if (subscription == null) {
            return SubscriptionDetailState(
                subscriptionId = inputs.subscriptionId,
                loading = false,
                notFound = true,
                deleted = inputs.ephemeral.deleted,
            )
        }
        val group = inputs.groups.firstOrNull { it.id == subscription.groupId }
        return SubscriptionDetailState(
            subscriptionId = inputs.subscriptionId,
            loading = false,
            notFound = false,
            groupName = group?.name.orEmpty(),
            profileCount = group?.profiles?.size ?: 0,
            redactedUrl = redactUrl(subscription.url),
            lastFetchState = subscription.toLastFetchState(),
            // UserInfo.usedBytes defaults an absent upload/download to zero (its own KDoc);
            // correct only when the provider sent at least one of the two — see QuotaBar's own
            // "draw nothing you cannot feed" rule, which ServersViewModel.buildState already
            // applies for the identical reason.
            quotaUsedBytes = inputs.userInfo?.takeIf { it.upload != null || it.download != null }?.usedBytes,
            quotaTotalBytes = inputs.userInfo?.total,
            hwidEnabled = subscription.hwidEnabled,
            userAgentOverride = subscription.userAgentOverride,
            providerUserAgent = inputs.providerUserAgent,
            rows = inputs.rows,
            isRefreshing = inputs.ephemeral.isRefreshing,
            refreshResult = inputs.ephemeral.refreshResult,
            deleted = inputs.ephemeral.deleted,
        )
    }

    private data class DetailInputs(
        val subscriptionId: Long,
        val subscription: StoredSubscription?,
        val groups: List<ProfileGroup>,
        val userInfo: UserInfo?,
        val rows: List<DirectiveRow>,
        val providerUserAgent: String?,
        val ephemeral: Ephemeral,
    )

    private data class DetailSettings(
        val rows: List<DirectiveRow>,
        val providerUserAgent: String?,
    )

    /**
     * [art.yniyniyni.subspace.feature.profiles.list.ServersGroupList]'s Update button, mirrored
     * here for this one subscription.
     */
    fun onRefreshNow() {
        val id = loadedId ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            ephemeral.value = ephemeral.value.copy(isRefreshing = true, refreshResult = null)
            val result = profileSource.syncSubscription(id)
            ephemeral.value = ephemeral.value.copy(isRefreshing = false, refreshResult = result.toUserMessage())
        }
    }

    fun onDismissRefreshResult() {
        ephemeral.value = ephemeral.value.copy(refreshResult = null)
    }

    /** Pins [value] for [key] — spec D3: the user's pin, once set, survives every subsequent provider update. */
    fun onPinRow(
        key: String,
        value: String,
    ) {
        val id = loadedId ?: return
        val canonical =
            value
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let { DirectiveRegistry.specs[key]?.kind?.canonicalise(it) }
                as? KindResult.Canonical
                ?: return
        viewModelScope.launch { profileSource.pin(id, key, canonical.value) }
    }

    /** Hands [key] back to the provider. */
    fun onUnpinRow(key: String) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.unpin(id, key) }
    }

    fun onHwidEnabledChanged(enabled: Boolean) {
        val id = loadedId ?: return
        viewModelScope.launch { profileSource.setHwidEnabled(id, enabled) }
    }

    /** [userAgent] blank or `null` clears the override — see [ProfileSource.setUserAgentOverride]. */
    fun onUserAgentOverrideChanged(userAgent: String?) {
        val id = loadedId ?: return
        val canonical =
            if (userAgent.isNullOrBlank()) {
                null
            } else {
                canonicalUserAgent(userAgent) ?: return
            }
        viewModelScope.launch { profileSource.setUserAgentOverride(id, canonical) }
    }

    /**
     * Deletes this subscription — its group, its servers, its directives and its overrides
     * (spec §A.1: deletion must cascade). The caller confirms first; see [SubscriptionDetailState.deleted]'s
     * own KDoc for how the screen tells "the user just deleted this" from "this id was never
     * valid" apart.
     */
    fun onDelete() {
        val id = loadedId ?: return
        viewModelScope.launch {
            profileSource.deleteSubscription(id)
            ephemeral.value = ephemeral.value.copy(deleted = true)
        }
    }

    private data class Ephemeral(
        val isRefreshing: Boolean = false,
        val refreshResult: UserMessage? = null,
        val deleted: Boolean = false,
    )
}
