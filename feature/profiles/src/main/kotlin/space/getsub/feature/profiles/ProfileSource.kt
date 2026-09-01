// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.profiles

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import space.getsub.core.data.AddedSubscription
import space.getsub.core.data.EffectiveValue
import space.getsub.core.data.ProfileGroup
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.StoredProfile
import space.getsub.core.data.StoredSubscription
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.sync.SubscriptionSyncer
import space.getsub.core.data.sync.SyncResult
import space.getsub.core.model.DnsResolver
import space.getsub.core.model.Outbound
import space.getsub.core.model.Profile
import space.getsub.core.parser.PassthroughRejection
import space.getsub.service.PassthroughValidator
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
 * [space.getsub.feature.home.ActiveProfileSource] exists for
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

    /**
     * Whether the app's own routing rule set or DNS resolver is active right now — the
     * signal Task 10's editor warning needs (spec §9): an active rule set, or a
     * non-default [SettingsRepository.dnsResolver], each replace a passthrough
     * profile's own `routing`/`dns` blocks wholesale. Collapses `:service`'s own
     * `passthroughPlanFor`'s `routingActive || dnsPlanPresent` to a settings-only
     * check, and both disjuncts here are load-bearing on their own: `dnsPlanPresent`
     * is `DnsPlanner.plan` returning non-null, which happens either because an active
     * *routing profile* carries its own DNS block (`RoutingResolution.Active.dns` —
     * that half genuinely does require routing to be active) **or** because
     * [SettingsRepository.dnsResolver] alone is non-default, per `DnsPlanner.kt`'s
     * `nothingToDo = effective == null && setting == DEFAULT_SETTING` — a case that
     * fires with routing fully off. `resolver != DnsResolver.DEFAULT` below is what
     * catches that second case; dropping it on the theory that the first term
     * subsumes it would silently stop the warning firing for a routing-off session
     * running a non-default resolver.
     *
     * Defaulted to `flowOf(false)` — unlike [activeProfileId]/[globalHwidEnabled]
     * above, only [space.getsub.feature.profiles.editor.EditorViewModel]
     * reads this today, so the other three fakes of this interface
     * (`ServersViewModelTest`, `ImportViewModelTest`, `SubscriptionDetailViewModelTest`)
     * need not implement it.
     */
    val routingOverridesPassthrough: Flow<Boolean>
        get() = flowOf(false)

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
     * Where [ImportViewModel][space.getsub.feature.profiles.add.ImportViewModel]
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
     * as of Task 21 — what [EditorViewModel][space.getsub.feature.profiles.editor.EditorViewModel]
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
     *   space.getsub.feature.profiles.editor.EditorViewModel]) reports this as a
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
     * see [EditorViewModel][space.getsub.feature.profiles.editor.EditorViewModel]'s
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
     * [ImportViewModel][space.getsub.feature.profiles.add.ImportViewModel]'s
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
     * ([space.getsub.core.parser.directive.parseUserInfo]), this
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

/**
 * The rule behind [ProfileSource.routingOverridesPassthrough], extracted to a standalone
 * top-level function for the same reason `TunnelService.kt`'s own `passthroughPlanFor` is: a
 * rule inlined into a `combine { ... }` lambda inside [BoundProfileSource]'s constructor can
 * only be exercised by constructing real (Room-backed, `internal`-constructor) repositories,
 * which no JVM test in this module can do — see this file's own KDoc. Pulled out here, it is
 * two plain values in and a `Boolean` out, testable without either.
 */
internal fun routingOverridesPassthroughFor(
    activeRuleSetId: Long?,
    resolver: DnsResolver,
): Boolean = activeRuleSetId != null || resolver != DnsResolver.DEFAULT

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

    override val routingOverridesPassthrough: Flow<Boolean> =
        combine(
            settingsRepository.activeRoutingRuleSetId,
            settingsRepository.dnsResolver,
            ::routingOverridesPassthroughFor,
        )

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
     *
     * Matched by content (`it.rawJson in candidates`), not by id — unlike [syncSubscription],
     * which must match by id (see that method's KDoc for why a content match is unsafe there).
     * Safe here because [candidates] is the literal, exact set of `rawJson` values *this call*
     * passed to [profileRepository]`.import`: every row [validateCoreEligibility] considers is
     * therefore either a row this call wrote, or a pre-existing row that merely happens to share
     * byte-identical text with one — validating that extra row too is redundant, never wrong.
     */
    override suspend fun import(
        profiles: List<Profile>,
        groupId: Long,
    ): Int {
        val written = profileRepository.import(profiles, groupId)
        val candidates = profiles.mapNotNull { it.rawJson }.toSet()
        if (candidates.isNotEmpty()) {
            validateCoreEligibility(groupId) { it.rawJson in candidates }
        }
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
     * eligible rows, keyed by **id**, not by `rawJson` text (review round 2, Important 6): a
     * plain `Set<String>` diff is blind to identity — if a row this sync genuinely adds or
     * updates carries `rawJson` text byte-identical to an already-eligible row elsewhere in the
     * group (`SubscriptionSyncer`'s own outbound-identity fallback for a fanned-out raw element
     * exists precisely because two rows can share identical `rawJson` under distinct
     * `identityHash`es), a content-set difference silently drops it and that row is never
     * validated — not now, and not on any later sync, since the same diff repeats the same miss.
     * [changedProfileIds] fixes this by comparing *per id*, so a row is a candidate exactly when
     * its id is new or its `rawJson` at that id changed, never merely because its text collides
     * with something already present. [SubscriptionSyncer.sync] reports only counts
     * ([SyncResult.Synced.added]/[SyncResult.Synced.updated]), never which rows, so there is no
     * cheaper way to name "this call's own documents" the way [import] can from its own
     * `profiles` argument. See [validateCoreEligibility]'s KDoc for what this scoping does and
     * does not catch.
     */
    @Suppress("ReturnCount")
    override suspend fun syncSubscription(id: Long): SyncResult {
        val subscription = subscriptionRepository.observeSubscriptions().first().firstOrNull { it.id == id }
        val before = subscription?.let { eligibleRawJsonsById(it.groupId) }.orEmpty()
        val result = subscriptionSyncer.sync(id)
        if (subscription == null) return result
        val synced = result as? SyncResult.Synced ?: return result
        if (synced.added > 0 || synced.updated > 0) {
            val after = eligibleRawJsonsById(subscription.groupId)
            val candidateIds = changedProfileIds(before, after)
            if (candidateIds.isNotEmpty()) {
                validateCoreEligibility(subscription.groupId) { it.id in candidateIds }
            }
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
     * Task 8: runs every [StoredProfile] in [groupId] for which [isCandidate] is true through
     * [passthroughValidator], provided its structural verdict is still eligible
     * ([StoredProfile.runsAsWritten]), and records [PassthroughRejection.CoreRejected] for
     * whichever ones the real core refuses.
     *
     * [isCandidate] is what keeps this bounded to "what this call actually wrote", not the whole
     * group: an accepted row keeps a null verdict forever (nothing ever confirms it positively),
     * so re-scanning every eligible row in the group on every call would re-run `testXray` —
     * a real native core start — against every already-approved row every single time, turning
     * the Nth import into a group into N native-core spins on a suspend call the user is
     * waiting on. [import] and [syncSubscription] each supply a predicate suited to what they
     * actually know (see their own KDocs for why those differ — content match is safe for one
     * and unsafe for the other), so a row from an earlier call is only revisited when this
     * call's own predicate says so.
     *
     * Re-queries the group for [StoredProfile]s (rather than working from whatever the callers
     * hold) to evaluate [isCandidate] and resolve each match's row id and current verdict — the
     * write itself uses [StoredProfile.id] directly, never a re-derived identity hash. A row
     * already marked [PassthroughRejection.CoreRejected] by an earlier pass is skipped rather
     * than re-asked.
     *
     * Scope, per the M7 ruling this task is bound by: this method only ever runs from
     * [import] and [syncSubscription], both entry points `:feature:profiles` owns. The
     * periodic subscription refresh (`SubscriptionRefreshWorker` in `:app`) calls
     * `SubscriptionSyncer.sync` directly and never reaches this class, so a config the core
     * would refuse is not caught there — it is caught at connect time instead
     * (`FailureReason.PassthroughRejectedAtConnect`, Task 9). Plumbing core validation through
     * that path would require `:core:data` to depend on `:core:xray`, which §4 forbids. The
     * same backstop also covers a row this method's [isCandidate] scoping never revisits (an
     * unrelated earlier import, or one [passthroughValidator] left undetermined because geo
     * assets were not installed yet) — it fails at connect with an accurate reason instead of
     * silently staying unvalidated forever.
     */
    private suspend fun validateCoreEligibility(
        groupId: Long,
        isCandidate: (StoredProfile) -> Boolean,
    ) {
        val group = profileRepository.observeGroups().first().firstOrNull { it.id == groupId } ?: return
        group.profiles
            .filter { it.runsAsWritten && isCandidate(it) }
            .forEach { profile ->
                val rawJson = profile.rawJson ?: return@forEach
                if (!passthroughValidator.validate(rawJson)) {
                    profileRepository.setPassthroughRejection(profile.id, PassthroughRejection.CoreRejected.name)
                }
            }
    }

    /** The group's own eligible ([StoredProfile.runsAsWritten]) rows, by id, with their `rawJson`. */
    private suspend fun eligibleRawJsonsById(groupId: Long): Map<Long, String> =
        profileRepository.observeGroups().first().firstOrNull { it.id == groupId }
            ?.profiles.orEmpty()
            .filter { it.runsAsWritten }
            .mapNotNull { profile -> profile.rawJson?.let { profile.id to it } }
            .toMap()
}

/**
 * Row ids in [after] that are new (absent from [before]) or whose `rawJson` changed, comparing
 * **per id** rather than as two content sets.
 *
 * Review round 2, Important 6: the version this replaced computed `after - before` on
 * `Set<String>`, which is blind to identity. Two distinct rows can legitimately share
 * byte-identical `rawJson` (`SubscriptionSyncer`'s outbound-identity fallback for a fanned-out
 * raw element is exactly this shape), and under a plain set difference, a newly added row whose
 * text happens to match an already-eligible row elsewhere in the group vanishes from the
 * difference — it is silently never validated, on this sync or any later one. Comparing the
 * value at each id instead — new id, or same id with different text — cannot make that mistake:
 * an id present in both [before] and [after] with unchanged text is excluded regardless of what
 * any other id's `rawJson` looks like.
 */
internal fun changedProfileIds(
    before: Map<Long, String>,
    after: Map<Long, String>,
): Set<Long> = after.filterKeys { rowId -> before[rowId] != after[rowId] }.keys
