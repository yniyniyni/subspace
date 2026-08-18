// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place the effective per-app selection is computed (§8).
 *
 * "Effective" rather than "selected": both the picker and the tunnel read
 * [selection], so they cannot disagree about which packages are in force. A
 * second derivation somewhere else is exactly the divergence §5.5 warns about,
 * one layer down.
 *
 * Part 2 extends [combineSelection] with approved provider layers — union with
 * the user's set, never replacing it. That is a change to this one function body
 * rather than a new source of truth, which is the whole reason the computation
 * is here and not inlined at its two call sites.
 */
@Singleton
public class PerAppRepository
@Inject
constructor(
    private val settings: SettingsRepository,
) {
    /** The mode and the packages in force under it. */
    public val selection: Flow<PerAppSelection> =
        combine(settings.perAppMode, settings.perAppUserPackages) { mode, userPackages ->
            combineSelection(mode, userPackages)
        }

    public suspend fun setMode(mode: PerAppMode) {
        settings.setPerAppMode(mode)
    }

    public suspend fun setUserPackages(packages: Set<String>) {
        settings.setPerAppUserPackages(packages)
    }
}

/**
 * Spec §4.3, and the two asymmetries that matter.
 *
 * [PerAppMode.Off] reports no packages at all, so a selection left behind by a
 * mode the user switched off cannot reach `VpnService.Builder`.
 *
 * An empty **deny**-list collapses to [PerAppSelection.OFF] because the two are
 * behaviourally identical — every app is tunnelled — and collapsing means that
 * case runs M1's proven code path instead of a second path that merely agrees
 * with it today.
 *
 * An empty **allow**-list does not collapse. It is a TUN interface no application
 * may use: connected, no traffic, no error, which is §10.1's exact signature. It
 * survives to `PerAppResolver`, which refuses the start with a named failure.
 */
internal fun combineSelection(
    mode: PerAppMode,
    userPackages: Set<String>,
): PerAppSelection {
    return when {
        mode == PerAppMode.Off -> PerAppSelection.OFF
        mode == PerAppMode.DenyList && userPackages.isEmpty() -> PerAppSelection.OFF
        else -> PerAppSelection(mode, userPackages)
    }
}
