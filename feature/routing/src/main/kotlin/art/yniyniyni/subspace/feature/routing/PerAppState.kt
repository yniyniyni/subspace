// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.model.PerAppMode

/**
 * What the per-app picker shows.
 *
 * @param rows already filtered by [query] — the screen renders what it is given
 *   and never filters again, so a search box and a list cannot disagree.
 * @param selectedCount how many packages the draft has ticked **in total**, not
 *   how many are ticked among [rows]. The two differ the moment a query is
 *   typed, and every decision that is about the selection rather than about
 *   what is on screen — the count text, [isEmptyAllowList] — reads this. A
 *   search must never be able to change what the user has chosen, nor what the
 *   screen believes they have chosen.
 * @param isDirty whether the draft differs from what is stored. Drives both the
 *   Save affordance and the leave-confirmation (spec §7.3).
 * @param isTunnelActive whether a tunnel is up **or coming up**, so the screen
 *   can say a save will reconnect it before the user commits rather than after.
 *   See [PerAppSource.isTunnelActive] for why `Connecting` counts.
 */
internal data class PerAppState(
    val mode: PerAppMode = PerAppMode.Off,
    val rows: List<AppRow> = emptyList(),
    val selectedCount: Int = 0,
    val query: String = "",
    val isDirty: Boolean = false,
    val isTunnelActive: Boolean = false,
) {
    /**
     * Allow-list mode with nothing ticked. The screen blocks Save on this and
     * `PerAppResolver` refuses to start on it — the UI gate is a hint, the
     * service gate is the guarantee (§6.3), the same split
     * [RoutingViewModel.activate] makes for `canActivate`.
     *
     * Read from [selectedCount] rather than from [rows] deliberately: `rows` is
     * query-filtered, so deriving it there would raise the warning and disable
     * Save as soon as a search happened to match nothing — a security warning
     * about a state the user is not in, and no way to save the state they are in.
     */
    val isEmptyAllowList: Boolean
        get() = mode == PerAppMode.AllowList && selectedCount == 0
}

/**
 * One application's row.
 *
 * §5.6: a package name identifies an app the user has installed, so it is never
 * logged. Rendering one on the user's own screen is not a disclosure.
 *
 * @param isInstalled false for a package selected earlier and uninstalled since.
 *   Shown and removable, never auto-pruned (spec §7.2).
 */
internal data class AppRow(
    val packageName: String,
    val label: String,
    val isSelected: Boolean,
    val isInstalled: Boolean = true,
)
