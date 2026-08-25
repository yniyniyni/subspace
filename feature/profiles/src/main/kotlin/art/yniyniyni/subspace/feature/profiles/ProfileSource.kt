// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles

import art.yniyniyni.subspace.core.data.AddedSubscription
import art.yniyniyni.subspace.core.data.EffectiveValue
import art.yniyniyni.subspace.core.data.ProfileGroup
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.data.StoredSubscription
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.sync.SubscriptionSyncer
import art.yniyniyni.subspace.core.data.sync.SyncResult
import art.yniyniyni.subspace.core.model.Outbound
import art.yniyniyni.subspace.core.model.Profile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** `DirectiveRegistry`'s key for the quota/usage header Task 14's `GroupCard` renders. */
private const val KEY_SUBSCRIPTION_USERINFO = "subscription-userinfo"

/**
 * The [ProfileRepository], [SettingsRepository], [SubscriptionRepository] and
 * [SubscriptionSyncer] surface `:feature:profiles` needs, folded into one seam.
 *
 * All four have `internal` constructors scoped to `:core:data` (§3 keeps
 * settings and subscriptions behind typed repositories, not a key/value
 * table any module can poke), so this module cannot build a real instance of
 * any of them to test against — the same reason
 * [art.yniyniyni.subspace.feature.home.ActiveProfileSource] exists for
 * `:feature:home`. [BoundProfileSource] is the one place that touches the
 * real repositories; every screen in this module (the Servers list, the
 * editor, and Task 13's subscription-add route) goes through this interface
 * instead, so a plain JVM test can exercise them against a fake.
 */
// One seam over four repositories on purpose — see ProfileRepository's own identical suppression.
@Suppress("TooManyFunctions")
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

    /** Whether the Settings-level Device ID gate permits any subscription to send its ID. */
    val globalHwidEnabled: Flow<Boolean>

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
     * [Profile.rawJson] means (§6: non-null only for a profile from a hand-pasted config,
     * carrying that config's own bytes) and what the returned count is (rows actually
     * written, not `profiles.size`).
     */
    suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
    ): Int

    /**
     * A single profile by its row id, or `null` if it no longer exists. Lets a
     * caller confirm what was actually persisted, e.g. after [import], or —
     * as of Task 21 — what [EditorViewModel][art.yniyniyni.subspace.feature.profiles.editor.EditorViewModel]
     * loads for editing.
     */
    suspend fun profile(id: Long): StoredProfile?

    /** Renames a profile. A no-op if it no longer exists. Task 21: the editor's only write for a RAW_JSON profile. */
    suspend fun rename(
        id: Long,
        name: String,
    )

    /**
     * Moves a profile to a different group. A no-op (returns `true`) if it no longer
     * exists.
     *
     * @return `false` if [toGroupId] already holds a profile with an identical outbound —
     *   see [ProfileRepository.move]'s KDoc. Task 21: the caller ([EditorViewModel][
     *   art.yniyniyni.subspace.feature.profiles.editor.EditorViewModel]) reports this as a
     *   diagnostic rather than letting the underlying `SQLiteConstraintException` crash the
     *   app.
     */
    suspend fun move(
        id: Long,
        toGroupId: Long,
    ): Boolean

    /**
     * Rewrites a TYPED profile's name and whole outbound in place — see
     * [ProfileRepository.update]'s KDoc. Never called for a RAW_JSON profile;
     * see [EditorViewModel][art.yniyniyni.subspace.feature.profiles.editor.EditorViewModel]'s
     * `save` for the branch that enforces this.
     *
     * @return `false` if the edited outbound now collides with another profile already in
     *   this profile's group — see [ProfileRepository.update]'s KDoc. `true` otherwise.
     */
    suspend fun update(
        id: Long,
        name: String,
        outbound: Outbound,
    ): Boolean

    /**
     * Adds a subscription and the group that holds its servers — see
     * [SubscriptionRepository.add]. Task 13:
     * [ImportViewModel][art.yniyniyni.subspace.feature.profiles.add.ImportViewModel]'s
     * "From subscription URL" route.
     */
    suspend fun addSubscription(
        url: String,
        name: String,
    ): AddedSubscription

    /** Runs one sync of [id] — see [SubscriptionSyncer.sync]. */
    suspend fun syncSubscription(id: Long): SyncResult

    /** Deletes a subscription and its group — see [SubscriptionRepository.delete]. */
    suspend fun deleteSubscription(id: Long)

    /**
     * Every stored subscription — Task 14: lets the Servers screen map a
     * `SUBSCRIPTION`-sourced [ProfileGroup] (via [StoredSubscription.groupId])
     * to the provider metadata it owns.
     */
    fun observeSubscriptions(): Flow<List<StoredSubscription>>

    /**
     * The raw `subscription-userinfo` value [id]'s provider last sent — pin,
     * else provider, else `null`, per [SubscriptionRepository.observeEffective].
     * `null` when the provider has sent no usable value; parsing the raw
     * semicolon-separated header is the caller's job
     * ([art.yniyniyni.subspace.core.parser.directive.parseUserInfo]), this
     * seam only resolves precedence.
     */
    fun observeUserInfo(id: Long): Flow<String?>

    /**
     * Resolves [key] for [id] under spec D3's precedence (pin, else provider, else [default]),
     * recomposing on every directive or pin change — see
     * [SubscriptionRepository.observeEffective]. Task 15: the subscription detail screen's own
     * `SettingRowState` rows.
     */
    fun observeEffective(
        id: Long,
        key: String,
        default: String?,
    ): Flow<EffectiveValue>

    /** Pins [value] for [key] on [id], so no future provider update moves it — see [SubscriptionRepository.pin]. */
    suspend fun pin(
        id: Long,
        key: String,
        value: String,
    )

    /** Removes a pin, handing [key] back to the provider — see [SubscriptionRepository.unpin]. */
    suspend fun unpin(
        id: Long,
        key: String,
    )

    /** Toggles the HWID header for [id] — see [SubscriptionRepository.setHwidEnabled]. */
    suspend fun setHwidEnabled(
        id: Long,
        enabled: Boolean,
    )

    /** Sets or clears (`null`/blank) [id]'s User-Agent override — see [SubscriptionRepository.setUserAgentOverride]. */
    suspend fun setUserAgentOverride(
        id: Long,
        userAgent: String?,
    )
}

@Suppress("TooManyFunctions") // Implements ProfileSource — see that interface's own identical call.
@Singleton
internal class BoundProfileSource
@Inject
constructor(
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionSyncer: SubscriptionSyncer,
) : ProfileSource {
    override fun observeGroups(
        query: String,
        protocol: String?,
    ): Flow<List<ProfileGroup>> = profileRepository.observeGroups(query, protocol)

    override val activeProfileId: Flow<Long?> = settingsRepository.activeProfileId

    override val globalHwidEnabled: Flow<Boolean> = settingsRepository.hwidEnabled

    override suspend fun setActiveProfile(id: Long?) = settingsRepository.setActiveProfile(id)

    override suspend fun renameGroup(
        id: Long,
        name: String,
    ) = profileRepository.renameGroup(id, name)

    // Routed through SubscriptionRepository, not ProfileRepository: a
    // subscription-backed group owns routing profiles and their geo/sets trees,
    // and the plain group cascade removes neither the active-routing setting
    // nor those files. It forwards to ProfileRepository for an ordinary group.
    override suspend fun deleteGroup(id: Long) = subscriptionRepository.deleteGroup(id)

    override suspend fun defaultGroupId(): Long = profileRepository.defaultGroupId()

    override suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
    ): Int = profileRepository.import(profiles, groupId)

    override suspend fun profile(id: Long): StoredProfile? = profileRepository.profile(id)

    override suspend fun rename(
        id: Long,
        name: String,
    ) = profileRepository.rename(id, name)

    override suspend fun move(
        id: Long,
        toGroupId: Long,
    ): Boolean = profileRepository.move(id, toGroupId)

    override suspend fun update(
        id: Long,
        name: String,
        outbound: Outbound,
    ): Boolean = profileRepository.update(id, name, outbound)

    override suspend fun addSubscription(
        url: String,
        name: String,
    ): AddedSubscription = subscriptionRepository.add(url, name)

    override suspend fun syncSubscription(id: Long): SyncResult = subscriptionSyncer.sync(id)

    override suspend fun deleteSubscription(id: Long) = subscriptionRepository.delete(id)

    override fun observeSubscriptions(): Flow<List<StoredSubscription>> = subscriptionRepository.observeSubscriptions()

    override fun observeUserInfo(id: Long): Flow<String?> =
        subscriptionRepository.observeEffective(id, KEY_SUBSCRIPTION_USERINFO, default = null).map { it.value }

    override fun observeEffective(
        id: Long,
        key: String,
        default: String?,
    ): Flow<EffectiveValue> = subscriptionRepository.observeEffective(id, key, default)

    override suspend fun pin(
        id: Long,
        key: String,
        value: String,
    ) = subscriptionRepository.pin(id, key, value)

    override suspend fun unpin(
        id: Long,
        key: String,
    ) = subscriptionRepository.unpin(id, key)

    override suspend fun setHwidEnabled(
        id: Long,
        enabled: Boolean,
    ) = subscriptionRepository.setHwidEnabled(id, enabled)

    override suspend fun setUserAgentOverride(
        id: Long,
        userAgent: String?,
    ) = subscriptionRepository.setUserAgentOverride(id, userAgent)
}
