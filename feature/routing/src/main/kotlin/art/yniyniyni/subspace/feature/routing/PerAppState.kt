// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.model.PerAppMode

/**
 * What the per-app picker shows.
 *
 * @param rows already filtered by [query] — the screen renders what it is given
 *   and never filters again, so a search box and a list cannot disagree.
 * @param isDirty whether the draft differs from what is stored. Drives both the
 *   Save affordance and the leave-confirmation (spec §7.3).
 * @param isConnected whether saving will restart the tunnel, so the screen can
 *   say so before the user commits rather than after.
 */
internal data class PerAppState(
    val mode: PerAppMode = PerAppMode.Off,
    val rows: List<AppRow> = emptyList(),
    val query: String = "",
    val isDirty: Boolean = false,
    val isConnected: Boolean = false,
) {
    /**
     * Allow-list mode with nothing ticked. The screen blocks Save on this and
     * `PerAppResolver` refuses to start on it — the UI gate is a hint, the
     * service gate is the guarantee (§6.3), the same split
     * [RoutingViewModel.activate] makes for `canActivate`.
     */
    val isEmptyAllowList: Boolean
        get() = mode == PerAppMode.AllowList && rows.none { it.isSelected }
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
