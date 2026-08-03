// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.data.ProfileKind

/** The display label for "no protocol filter" — always [ServersState.availableProtocols]' first entry. */
internal const val ALL_PROTOCOLS_LABEL = "All"

/**
 * How [ServersScreen] orders each group's rows.
 *
 * Deliberately three entries, not four: a "Fastest" order needs a real
 * latency measurement, and latency testing is M4 (ARCHITECTURE.md's roadmap).
 * [SortOrder.entries] is pinned by a test precisely so a future edit cannot
 * quietly add that fourth entry ahead of M4 actually having a number to sort
 * by — see ARCHITECTURE.md §10.1 on inventing numbers that look measured.
 */
internal enum class SortOrder { Alphabetical, AsListed, LastUsed }

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
 *   [ALL_PROTOCOLS_LABEL].
 */
internal data class ServersState(
    val groups: List<ServersGroup> = emptyList(),
    val query: String = "",
    val protocolFilter: String = ALL_PROTOCOLS_LABEL,
    val sort: SortOrder = SortOrder.AsListed,
    val availableProtocols: List<String> = listOf(ALL_PROTOCOLS_LABEL),
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
 */
internal data class ServersGroup(
    val id: Long,
    val name: String,
    val totalProfileCount: Int,
    val profiles: List<ServerRow>,
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
 * @property connectable mirrors [art.yniyniyni.subspace.core.data.StoredProfile.connectable].
 *   `:core:xray` only emits VLESS; every other protocol fails at connect time
 *   with `ProtocolNotSupported`. Task 17 left this unchecked in Home's
 *   `canConnect` — surfaced here so a user can see, before picking a server,
 *   which ones this build can actually connect to.
 * @property isActive whether this is the profile [art.yniyniyni.subspace.core.data.SettingsRepository.activeProfileId]
 *   currently names.
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
)
