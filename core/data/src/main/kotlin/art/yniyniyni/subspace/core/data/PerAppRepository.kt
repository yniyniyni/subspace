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
 * Two flows come out of here and they are **not** interchangeable:
 *
 * - [selection] is the **effective** set — what the tunnel is built with. It is
 *   deliberately lossy: [combineSelection] collapses whole categories of stored
 *   state down to [PerAppSelection.OFF] so a mode the user switched off cannot
 *   reach `VpnService.Builder`.
 * - [userSelection] is the **raw** set — exactly what is in Room, nothing
 *   collapsed. It is what the picker edits, because the picker round-trips it:
 *   it seeds its draft from this and writes back through [setUserPackages].
 *
 * Reading the effective flow into an editor and writing the raw one back is a
 * lossy round trip — a deny-list saved, switched to `Off`, then re-opened would
 * come back empty and the next save would erase it for real. Which flow you want
 * is decided by whether you *consume* the selection (effective) or *edit* it
 * (raw); there is no third answer, and no second place either is derived.
 *
 * Part 2 extends [combineSelection] with approved provider layers — union with
 * the user's set, never replacing it. That is a change to this one function body
 * rather than a new source of truth, which is the whole reason the computation
 * is here and not inlined at its two call sites. Note that it extends the
 * *effective* flow only: a provider layer must never be written back into the
 * user's own row, so [userSelection] stays raw under Part 2 as well.
 */
@Singleton
public class PerAppRepository
@Inject
constructor(
    private val settings: SettingsRepository,
) {
    /**
     * The **effective** selection: the mode and the packages in force under it.
     *
     * This is what `:service` resolves a start against. See [combineSelection]
     * for the two cases it collapses, and the class KDoc for why an editor must
     * not read it.
     */
    public val selection: Flow<PerAppSelection> =
        combine(settings.perAppMode, settings.perAppUserPackages) { mode, userPackages ->
            combineSelection(mode, userPackages)
        }

    /**
     * The **raw** selection the user last saved, uncollapsed.
     *
     * The picker reads this and only this. The mode is reported as stored even
     * when it is [PerAppMode.Off], and the packages are reported whatever the
     * mode is — a list parked behind `Off` is still the user's list, and it must
     * come back ticked when they switch a mode on again. `:service` must never
     * read it: it says what the user chose, not what the tunnel does, and the
     * difference between the two is exactly [combineSelection].
     */
    public val userSelection: Flow<PerAppSelection> =
        combine(settings.perAppMode, settings.perAppUserPackages) { mode, userPackages ->
            PerAppSelection(mode, userPackages)
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
