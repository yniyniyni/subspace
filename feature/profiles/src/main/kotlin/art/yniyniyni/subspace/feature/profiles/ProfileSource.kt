// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles

import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.Profile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ProfileRepository] and [SettingsRepository] surface `:feature:profiles`
 * needs, folded into one seam.
 *
 * Both repositories have `internal` constructors scoped to `:core:data` (§3
 * keeps settings behind a typed repository, not a key/value table any module
 * can poke), so this module cannot build a real instance of either to test
 * against — the same reason [art.yniyniyni.subspace.feature.home.ActiveProfileSource]
 * exists for `:feature:home`. [BoundProfileSource] is the one place that
 * touches the real repositories; every screen in this module (the Servers
 * list now, the editor and subscription management later) goes through this
 * interface instead, so a plain JVM test can exercise them against a fake.
 */
internal interface ProfileSource {
    /**
     * Every group, in display order, filtered to the profiles matching
     * [query] (name, address or transport, case-insensitive) and [protocol]
     * (exact match; `null` means every protocol) — see
     * [ProfileRepository.observeGroups] for what runs this in SQL rather
     * than in memory.
     */
    fun observeGroups(
        query: String,
        protocol: String?,
    ): Flow<List<ProfileGroup>>

    /** The profile [SettingsRepository.activeProfileId] currently names, or `null`. */
    val activeProfileId: Flow<Long?>

    /** Sets the active profile, or clears it when [id] is `null`. */
    suspend fun setActiveProfile(id: Long?)

    /** Renames a group in place. A no-op if it no longer exists. */
    suspend fun renameGroup(
        id: Long,
        name: String,
    )

    /** Deletes a group. `ON DELETE CASCADE` removes every profile in it with it. */
    suspend fun deleteGroup(id: Long)

    /**
     * The id of the *Local configs* group (spec D3), creating it on first call.
     * Where [ImportViewModel][art.yniyniyni.subspace.feature.profiles.add.ImportViewModel]
     * lands newly imported profiles — this screen has no group picker yet.
     */
    suspend fun defaultGroupId(): Long

    /**
     * Imports [profiles] into [groupId]; see [ProfileRepository.import] for what
     * [rawJson] means (§6: non-null only for a hand-pasted config, stored byte-for-byte).
     */
    suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
        rawJson: String?,
    )

    /**
     * A single profile by its row id, or `null` if it no longer exists. Lets a
     * caller confirm what was actually persisted, e.g. after [import].
     */
    suspend fun profile(id: Long): StoredProfile?
}

@Singleton
internal class BoundProfileSource
@Inject
constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ProfileSource {
    override fun observeGroups(
        query: String,
        protocol: String?,
    ): Flow<List<ProfileGroup>> = profileRepository.observeGroups(query, protocol)

    override val activeProfileId: Flow<Long?> = settingsRepository.activeProfileId

    override suspend fun setActiveProfile(id: Long?) = settingsRepository.setActiveProfile(id)

    override suspend fun renameGroup(
        id: Long,
        name: String,
    ) = profileRepository.renameGroup(id, name)

    override suspend fun deleteGroup(id: Long) = profileRepository.deleteGroup(id)

    override suspend fun defaultGroupId(): Long = profileRepository.defaultGroupId()

    override suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
        rawJson: String?,
    ) = profileRepository.import(profiles, groupId, rawJson)

    override suspend fun profile(id: Long): StoredProfile? = profileRepository.profile(id)
}
