// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.subscription

import androidx.annotation.StringRes
import art.yniyniyni.subspace.core.data.EffectiveValue
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.data.isDirectiveEnabled
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncFailure
import art.yniyniyni.subspace.feature.profiles.add.UserMessage
import java.net.URI

private const val REDACTED_PATH_SUFFIX = "/…"
private const val REDACTED_FALLBACK = "…"
private const val NO_SERVERS_STATUS = "NoServers"

/**
 * One pinnable directive's resolved value, alongside enough of spec D3's precedence for the UI
 * to explain itself.
 *
 * Spec D3's UI consequence: a pinned field that shows only its own value is indistinguishable
 * from a subscription that stopped updating — the declined provider value must stay visible.
 *
 * @property key the directive's registry key, e.g. `profile-update-interval` — closed-vocabulary,
 *   safe to carry and to pass back to `pin`/`unpin` (same reasoning [EffectiveValue.key]'s own
 *   KDoc gives).
 * @property value the effective value: pin, else provider, else default. Never rendered as a
 *   dash or a substituted default the caller invented — see [SubscriptionDetailState.rows]' own
 *   KDoc for how a row only exists here when this project actually reads the key.
 * @property declinedProviderValue the provider's own value, but only when it is being
 *   overridden by a pin the user actually set — `null` whenever there is nothing to nag about
 *   ([showsDeclinedProviderValue]).
 */
internal data class SettingRowState(
    val key: String,
    val value: String?,
    val declinedProviderValue: String?,
    val isPinned: Boolean,
) {
    /**
     * `true` only when a pin is in effect, the provider sent a value, and the two disagree —
     * the exact condition spec D3 requires the UI to surface ("provider suggests 6 h · you
     * pinned 24 h"). A pin that happens to agree with the provider does not nag, and a pin the
     * provider has never spoken to (it sent no value for this key at all) has nothing to
     * decline.
     */
    val showsDeclinedProviderValue: Boolean get() = declinedProviderValue != null

    /** The shared §A.1 boolean interpretation of [value] for a toggle row. */
    val isEnabled: Boolean get() = isDirectiveEnabled(value)

    companion object {
        /** Resolves [effective] into what one setting row renders — spec D3's precedence, restated for display. */
        fun from(effective: EffectiveValue): SettingRowState {
            val declined =
                if (
                    effective.isPinned &&
                    effective.providerValue != null &&
                    effective.providerValue != effective.value
                ) {
                    effective.providerValue
                } else {
                    null
                }
            return SettingRowState(
                key = effective.key,
                value = effective.value,
                declinedProviderValue = declined,
                isPinned = effective.isPinned,
            )
        }
    }
}

/**
 * How [SubscriptionDetailScreen] renders one [SettingRowState] — its unit and its edit
 * affordance.
 */
internal enum class DirectiveRowKind { Hours, Seconds, Toggle }

/** One directive row: [row]'s resolved value plus enough to render and edit it. */
internal data class DirectiveRow(
    val kind: DirectiveRowKind,
    @param:StringRes val labelRes: Int,
    val row: SettingRowState,
)

/**
 * §7's failure taxonomy, resolved from [StoredSubscription]'s own two-column encoding
 * ([StoredSubscription.lastFetchStatus] holds a [SubscriptionSyncFailure] name, `NoServers`, or
 * `null` on server-bearing success) into something the screen can render without re-deriving that mapping — see
 * [toLastFetchState].
 */
internal sealed interface LastFetchState {
    /** No fetch has ever completed for this subscription — not even a failed one. */
    data object NeverFetched : LastFetchState

    data class Succeeded(val atEpochMillis: Long) : LastFetchState

    /**
     * @property lastSuccessAtEpochMillis the last time a fetch *did* succeed, or `null` if this
     *   subscription has never once synced successfully — distinct from [NeverFetched], which
     *   means no attempt has been made at all rather than every attempt having failed.
     */
    data class Failed(
        val reason: SubscriptionSyncFailure,
        val lastSuccessAtEpochMillis: Long?,
    ) : LastFetchState

    /** The response was valid enough to land metadata, but yielded no usable server profiles. */
    data class NoServers(val lastSuccessAtEpochMillis: Long?) : LastFetchState
}

/**
 * [StoredSubscription.lastFetchStatus]/[StoredSubscription.lastFetchDetail] are always written
 * together as [SubscriptionSyncFailure.name] ([SubscriptionSyncer][
 * art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer]'s `recordFetchFailure`) and cleared
 * together on the next success, so a status this cannot parse
 * back to a real [SubscriptionSyncFailure] is not a real provider outcome — [ServerError] is the
 * closest honest fallback (a write that could not be trusted, not something the URL or the user
 * did wrong), matching [SyncResult.ReconciliationConflict][
 * art.yniyniyni.subspace.core.data.sync.SyncResult.ReconciliationConflict]'s own reuse of that
 * same string in `ImportViewModel.toUserMessage()`.
 */
internal fun StoredSubscription.toLastFetchState(): LastFetchState {
    val status = lastFetchStatus
    return when {
        status == null -> lastFetchedAt?.let { LastFetchState.Succeeded(it) } ?: LastFetchState.NeverFetched
        status == NO_SERVERS_STATUS -> LastFetchState.NoServers(lastFetchedAt)
        else -> {
            val reason =
                runCatching { SubscriptionSyncFailure.valueOf(status) }
                    .getOrDefault(SubscriptionSyncFailure.ServerError)
            LastFetchState.Failed(reason, lastFetchedAt)
        }
    }
}

/**
 * What [SubscriptionDetailScreen] renders.
 *
 * @property redactedUrl the subscription's URL with everything past the host stripped (§5.6) —
 *   see [redactUrl]. Blank only while [loading].
 * @property quotaUsedBytes/[quotaTotalBytes] `subscription-userinfo`'s own fields, forwarded
 *   straight to [art.yniyniyni.subspace.core.ui.component.QuotaBar] — `null` under the same
 *   "the provider sent nothing usable" conditions that component's own KDoc documents. Never a
 *   substituted zero (ARCHITECTURE.md §10.1, M3 spec §1.1): a `null` here draws nothing.
 * @property rows one row per user-facing directive this build actually reads — never all ~90
 *   registry keys. A row for a key nothing consumes would let a user pin a value that changes
 *   nothing, which is exactly the plausible-but-unwired UI M3 spec §1.1 forbids.
 * @property notFound `true` once the id this screen was given resolves to no stored
 *   subscription — a stale deep link, or the row this screen itself just [deleted].
 * @property deleted `true` once [id]'s own delete has actually completed, so the screen can
 *   navigate away on its own rather than the user reading a "not found" body for a delete they
 *   just asked for (see [notFound]'s own KDoc for the case this is *not* — an id that was never
 *   valid still renders the passive not-found body with a manual way back).
 */
internal data class SubscriptionDetailState(
    val subscriptionId: Long = 0L,
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val groupName: String = "",
    val profileCount: Int = 0,
    val redactedUrl: String = "",
    val lastFetchState: LastFetchState = LastFetchState.NeverFetched,
    val quotaUsedBytes: Long? = null,
    val quotaTotalBytes: Long? = null,
    val hwidEnabled: Boolean = true,
    /** The global Settings gate, separate from [hwidEnabled]'s per-subscription preference. */
    val globalHwidEnabled: Boolean = true,
    val userAgentOverride: String? = null,
    /** What the provider's `change-user-agent` directive currently suggests, if any. */
    val providerUserAgent: String? = null,
    val rows: List<DirectiveRow> = emptyList(),
    val isRefreshing: Boolean = false,
    val refreshResult: UserMessage? = null,
    val deleted: Boolean = false,
)

/**
 * §5.6: a subscription URL is a secret alongside server addresses and REALITY keys — this keeps
 * the origin (useful for telling two subscriptions apart at a glance) while dropping the path
 * and query string, which is where a provider's access token typically lives.
 *
 * A URL this project cannot even parse a scheme/host out of is redacted to the fixed
 * [REDACTED_FALLBACK] rather than shown verbatim — never a partial leak as a fallback.
 */
internal fun redactUrl(raw: String): String {
    val uri = runCatching { URI(raw) }.getOrNull()
    val scheme = uri?.scheme
    val host = uri?.host
    if (scheme.isNullOrBlank() || host.isNullOrBlank()) return REDACTED_FALLBACK

    val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
    return "$scheme://$host$port$REDACTED_PATH_SUFFIX"
}
