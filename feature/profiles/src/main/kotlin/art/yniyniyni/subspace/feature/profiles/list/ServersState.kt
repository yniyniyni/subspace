// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.feature.profiles.add.UserMessage

/**
 * The domain sentinel for "no protocol filter" — always [ServersState.availableProtocols]'
 * first entry, and what [ServersState.protocolFilter] equals until the user
 * picks a real protocol.
 *
 * Deliberately **not** the text shown on screen: fix round 1 found the
 * previous version of this file rendering this constant's value directly in
 * [ServersScreen]'s protocol chip, which bypassed `strings.xml` (§12) and
 * meant the sentinel a translator's string could never diverge from the
 * literal comparison value without breaking the filter. `ServersScreen`
 * instead resolves this sentinel to `R.string.servers_protocol_filter_all`
 * at render time — the same split [SortOrder]'s own `labelRes()` already
 * drew between its enum values (compared directly) and what they display as.
 */
internal const val ALL_PROTOCOLS_SENTINEL = "All"

/**
 * How a group's rows are ordered.
 *
 * [Fastest] was deliberately absent until M4.5. A "Fastest" order needs a real
 * latency measurement, and inventing one is exactly the failure ARCHITECTURE.md
 * §10.1 describes; the pinned `SortOrder.entries` test existed so that entry
 * could not appear before a number existed to sort by. M4.5 supplied the
 * measurement, so it exists now — and the test still pins the set, so adding a
 * *fifth* remains a deliberate act rather than a silent one.
 *
 * Ordering is resolved per group, not per screen: `subscriptions-sort-type` is
 * scoped to the subscription that delivered it (§A.1), so one global order would
 * let one provider rearrange another provider's rows.
 */
internal enum class SortOrder { Alphabetical, AsListed, LastUsed, Fastest }

/**
 * What [ServersScreen] renders.
 *
 * @property groups every group, each already filtered to [query]/[protocolFilter]
 *   and ordered by [sort]. A group with zero matching profiles still appears,
 *   with an empty [ServersGroup.profiles] — deleting or renaming a group is a
 *   structural action on the group itself, independent of whatever the user
 *   is currently searching for.
 * @property query the current search text, matched against a row's name,
 *   address and transport (never shown itself — see [ServerRow] for why
 *   address is not part of what a row exposes to the UI).
 * @property protocolFilter the selected chip label, one of [availableProtocols].
 * @property sort the selected ordering.
 * @property availableProtocols the protocol chips to render, derived from what
 *   is actually stored (never a hardcoded protocol list) and always led by
 *   [ALL_PROTOCOLS_SENTINEL].
 * @property updateResult the outcome of the most recent
 *   [GroupCard][art.yniyniyni.subspace.core.ui.component.GroupCard] update
 *   button press, or `null` when there is nothing to report. M4's device run
 *   found [ServersViewModel.onUpdateSubscription] discarding its [SyncResult][
 *   art.yniyniyni.subspace.core.data.sync.SyncResult] entirely, so a refresh
 *   that failed — including the HWID cases the milestone exists to
 *   distinguish — left the screen completely silent. The detail screen's
 *   `refreshResult` already did this; the card's button is the path that did
 *   not.
 */
internal data class ServersState(
    val groups: List<ServersGroup> = emptyList(),
    val query: String = "",
    val protocolFilter: String = ALL_PROTOCOLS_SENTINEL,
    val sort: SortOrder = SortOrder.AsListed,
    val availableProtocols: List<String> = listOf(ALL_PROTOCOLS_SENTINEL),
    val updateResult: UserMessage? = null,
)

/**
 * One folder of profiles, as the Servers screen renders it.
 *
 * @property totalProfileCount the group's real size, independent of any
 *   active search/filter — what [GroupCard][art.yniyniyni.subspace.core.ui.component.GroupCard]'s
 *   header shows and what a delete confirmation warns with, so narrowing the
 *   list with a search never understates how many profiles a delete removes.
 * @property profiles the rows currently visible under [ServersState.query]/[ServersState.protocolFilter],
 *   ordered by [ServersState.sort].
 * @property quotaUsedBytes Task 14: this group's subscription's parsed
 *   `subscription-userinfo` usage, forwarded to
 *   [GroupCard][art.yniyniyni.subspace.core.ui.component.GroupCard]'s
 *   `quotaUsedBytes`. `null` for a `MANUAL` group (it has no subscription to
 *   own one) and for a `SUBSCRIPTION` group whose provider has sent no
 *   measurable usage — never a substituted zero.
 * @property quotaTotalBytes the same directive's `total` field, forwarded to
 *   `GroupCard`'s `quotaTotalBytes`. `null` under the same conditions as
 *   [quotaUsedBytes], or when the provider omitted `total` entirely.
 * @property subscriptionId the id of the [art.yniyniyni.subspace.core.data.StoredSubscription]
 *   that owns this group, or `null` for a `MANUAL` group. Fix round (code
 *   review, three Important findings): this is what lets
 *   [ServersGroupList][art.yniyniyni.subspace.feature.profiles.list.ServersGroupList]
 *   wire `GroupCard`'s `onUpdate` to `syncSubscription(subscriptionId)` without
 *   `ServersGroup` itself carrying a lambda — the id is data, the callback is
 *   a Compose-layer concern built from it, same split [id]/[actions] already
 *   draws between this group and [ServersGroupListActions].
 * @property lastFetchedAtEpochMillis this group's subscription's
 *   [art.yniyniyni.subspace.core.data.StoredSubscription.lastFetchedAt],
 *   forwarded to `GroupCard`'s `lastFetchedAtEpochMillis`. `null` for a
 *   `MANUAL` group or a subscription never yet successfully fetched.
 */
internal data class ServersGroup(
    val id: Long,
    val name: String,
    val totalProfileCount: Int,
    val profiles: List<ServerRow>,
    val quotaUsedBytes: Long? = null,
    val quotaTotalBytes: Long? = null,
    val subscriptionId: Long? = null,
    val lastFetchedAtEpochMillis: Long? = null,
)

/**
 * One stored server, projected for display.
 *
 * Deliberately carries no address: §5.6 treats a server address as a secret,
 * and a server list is exactly where showing or logging one is tempting. The
 * node row this backs (ARCHITECTURE.md-facing brief for Task 18) renders a
 * code tile derived from [name], not [address][art.yniyniyni.subspace.core.data.StoredProfile.address].
 *
 * @property compatibilityMode mirrors [art.yniyniyni.subspace.core.data.StoredProfile.compatibilityMode].
 *   Shown as "compatibility mode", never "raw": passthrough execution of a
 *   hand-pasted config is not implemented (§6), and "raw" alone would promise
 *   behaviour this build does not have.
 * @property connectable mirrors [art.yniyniyni.subspace.core.data.StoredProfile.connectable] —
 *   see its own KDoc for what makes a row connectable (VLESS over `tcp`, `ws`, `grpc` or
 *   `xhttp`; other transports have no emission in `:core:xray`). Task 17 left this unchecked in
 *   Home's `canConnect` — surfaced here so a user can see, before picking a server, which ones
 *   this build can actually connect to. Getting that set wrong in the restrictive direction is
 *   how a working xhttp server came to be labelled unsupported, so this must never hardcode a
 *   transport list of its own.
 * @property isActive whether this is the profile [art.yniyniyni.subspace.core.data.SettingsRepository.activeProfileId]
 *   currently names.
 * @property droppedFromSubscriptionAt non-null when this active server was retained after its
 *   provider stopped offering it. The row renders a warning instead of silently making the
 *   orphaned server look current (spec D4).
 */
internal data class ServerRow(
    val id: Long,
    val name: String,
    val protocol: String,
    val protocolDisplay: String,
    val transport: String,
    val kind: ProfileKind,
    val compatibilityMode: Boolean,
    val connectable: Boolean,
    val isActive: Boolean,
    val droppedFromSubscriptionAt: Long? = null,
)
