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
import art.yniyniyni.subspace.core.parser.PassthroughRejection
import art.yniyniyni.subspace.service.PassthroughValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val passthroughValidator: PassthroughValidator,
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

    /**
     * Imports, then asks `:service`'s [PassthroughValidator] to run this call's own RAW_JSON
     * documents through the real core — the manual paste/deeplink path's half of Task 8's
     * wiring. Scoped to `profiles`' own `rawJson` values, not the whole group: see
     * [validateCoreEligibility]'s KDoc for why, and for what this deliberately does not cover.
     */
    override suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
    ): Int {
        val written = profileRepository.import(profiles, groupId)
        validateCoreEligibility(groupId, profiles.mapNotNull { it.rawJson }.toSet())
        return written
    }

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

    /**
     * Syncs, then runs the same core-eligibility pass [import] does over the subscription's
     * group — Task 8's other entry point. This is also what a first `addSubscription` call
     * runs through (`ImportViewModel.addSubscription` calls this immediately after adding), so
     * that path gets core validation without its own wiring. The **periodic** refresh path
     * (`SubscriptionRefreshWorker` via `RefreshScheduler` in `:app`) never calls this method —
     * it cannot reach `:core:xray` under §4 — so it stays on the structural verdict alone,
     * backstopped by `FailureReason.PassthroughRejectedAtConnect` at connect time.
     *
     * Runs only when [SyncResult.Synced] actually changed rows: a failed fetch or an
     * unchanged response has nothing new to validate, and re-running `testXray` against every
     * already-approved row on every sync would be pure waste.
     *
     * Scoped to what this sync actually changed via a before/after diff of the group's own
     * eligible `rawJson` set — [SubscriptionSyncer.sync] reports only counts
     * ([SyncResult.Synced.added]/[SyncResult.Synced.updated]), never which rows, so there is no
     * cheaper way to name "this call's own documents" the way [import] can from its own
     * `profiles` argument. See [validateCoreEligibility]'s KDoc for what this scoping does and
     * does not catch.
     */
    @Suppress("ReturnCount")
    override suspend fun syncSubscription(id: Long): SyncResult {
        val subscription = subscriptionRepository.observeSubscriptions().first().firstOrNull { it.id == id }
        val before = subscription?.let { eligibleRawJsons(it.groupId) }.orEmpty()
        val result = subscriptionSyncer.sync(id)
        if (subscription == null) return result
        val synced = result as? SyncResult.Synced ?: return result
        if (synced.added > 0 || synced.updated > 0) {
            validateCoreEligibility(subscription.groupId, eligibleRawJsons(subscription.groupId) - before)
        }
        return result
    }

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

    /**
     * Task 8: runs [candidates] — a subset of [groupId]'s own `rawJson` values, supplied by
     * [import]/[syncSubscription] — through [passthroughValidator] wherever the matching row's
     * structural verdict is still eligible ([StoredProfile.runsAsWritten]), and records
     * [PassthroughRejection.CoreRejected] for whichever ones the real core refuses.
     *
     * [candidates] is what keeps this bounded to "what this call actually wrote", not the whole
     * group: an accepted row keeps a null verdict forever (nothing ever confirms it positively),
     * so re-scanning every eligible row in the group on every call would re-run `testXray` —
     * a real native core start — against every already-approved row every single time, turning
     * the Nth import into a group into N native-core spins on a suspend call the user is
     * waiting on. Restricting to [candidates] means a row from an earlier call is only
     * revisited if this call's own diff says its `rawJson` is new or changed.
     *
     * Re-queries the group for [StoredProfile]s (rather than working from whatever the callers
     * hold) only to resolve each candidate's row id and current verdict — the write itself uses
     * [StoredProfile.id] directly, never a re-derived identity hash. A row already marked
     * [PassthroughRejection.CoreRejected] by an earlier pass is skipped rather than re-asked.
     *
     * Scope, per the M7 ruling this task is bound by: this method only ever runs from
     * [import] and [syncSubscription], both entry points `:feature:profiles` owns. The
     * periodic subscription refresh (`SubscriptionRefreshWorker` in `:app`) calls
     * `SubscriptionSyncer.sync` directly and never reaches this class, so a config the core
     * would refuse is not caught there — it is caught at connect time instead
     * (`FailureReason.PassthroughRejectedAtConnect`, Task 9). Plumbing core validation through
     * that path would require `:core:data` to depend on `:core:xray`, which §4 forbids. The
     * same backstop also covers a row this method's [candidates] scoping never revisits (an
     * unrelated earlier import, or one [passthroughValidator] left undetermined because geo
     * assets were not installed yet) — it fails at connect with an accurate reason instead of
     * silently staying unvalidated forever.
     */
    private suspend fun validateCoreEligibility(
        groupId: Long,
        candidates: Set<String>,
    ) {
        if (candidates.isEmpty()) return
        val group = profileRepository.observeGroups().first().firstOrNull { it.id == groupId } ?: return
        group.profiles
            .filter { it.runsAsWritten && it.rawJson in candidates }
            .forEach { profile ->
                val rawJson = profile.rawJson ?: return@forEach
                if (!passthroughValidator.validate(rawJson)) {
                    profileRepository.setPassthroughRejection(profile.id, PassthroughRejection.CoreRejected.name)
                }
            }
    }

    /** The group's own [StoredProfile.rawJson] values currently eligible ([StoredProfile.runsAsWritten]). */
    private suspend fun eligibleRawJsons(groupId: Long): Set<String> =
        profileRepository.observeGroups().first().firstOrNull { it.id == groupId }
            ?.profiles.orEmpty()
            .filter { it.runsAsWritten }
            .mapNotNull { it.rawJson }
            .toSet()
}
