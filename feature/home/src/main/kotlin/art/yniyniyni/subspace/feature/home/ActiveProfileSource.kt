// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.home

import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.StoredProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stored-profile half of [HomeState], as this screen needs to see it.
 *
 * Both [ProfileRepository] and [SettingsRepository] have `internal`
 * constructors scoped to `:core:data` (§3 keeps settings behind a typed
 * repository, not a key/value table any module can poke) — this module
 * cannot build a real instance of either to test against. Mirrors
 * [TunnelConnection]'s own reason for existing: the interface lets
 * [HomeViewModel] be exercised with a plain fake, and [BoundActiveProfileSource]
 * is the one place that touches the real repositories.
 */
internal interface ActiveProfileSource {
    /**
     * Whether any profile exists in any group, active or not. `false` only
     * for a genuinely empty store — the case that should point the user at
     * import rather than at a list with nothing in it.
     */
    val hasAnyProfile: Flow<Boolean>

    /** The profile [SettingsRepository.activeProfileId] currently names, or `null`. */
    val activeProfile: Flow<StoredProfile?>
}

@Singleton
internal class BoundActiveProfileSource
@Inject
constructor(
    profileRepository: ProfileRepository,
    settingsRepository: SettingsRepository,
) : ActiveProfileSource {
    private val allProfiles: Flow<List<StoredProfile>> =
        profileRepository.observeGroups().map { groups -> groups.flatMap { it.profiles } }

    override val hasAnyProfile: Flow<Boolean> =
        allProfiles.map { it.isNotEmpty() }.distinctUntilChanged()

    // Finding the row whose id matches the stored setting is not the
    // firstOrNull() shortcut this task retires — that took whichever
    // profile happened to be first; this looks up the one specific row the
    // user chose, by id, and is null when that id names none (a deleted
    // profile that is still recorded as active).
    override val activeProfile: Flow<StoredProfile?> =
        combine(allProfiles, settingsRepository.activeProfileId) { profiles, activeId ->
            profiles.firstOrNull { it.id == activeId }
        }
}
