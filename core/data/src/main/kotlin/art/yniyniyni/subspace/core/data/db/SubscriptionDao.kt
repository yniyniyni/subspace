// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import art.yniyniyni.subspace.core.parser.PassthroughRejection
import kotlinx.coroutines.flow.Flow

// Guaranteed to collide with no real identityHash (those are 64-char lowercase
// hex SHA-256 digests, see IdentityHash.kt) and, being suffixed with a row id,
// guaranteed distinct from every other row's placeholder too. See
// upsertBySubscriptionKey's KDoc for what this placeholder is for.
private const val TEMP_IDENTITY_HASH_PREFIX = "subspace-sync-temp:"

/**
 * One sync's whole computed change set, bundled so [SubscriptionDao.applySync] stays under
 * detekt's `LongParameterList` threshold instead of taking each field separately. Built by
 * `SubscriptionSyncer` and handed to [SubscriptionDao.applySync] as a single immutable value —
 * that handoff, one change set to one `@Transaction` method, is the point of spec §6.5.
 *
 * @property clearIds ids of existing rows whose `identityHash` [SubscriptionDao.applySync] must
 *   neutralise (via [SubscriptionDao.clearIdentityHash]) before writing any of [upserts]'s final
 *   data — every existing row whose *current* `identityHash` equals some surviving entity's
 *   *target* `identityHash`. `SubscriptionSyncer.reconcile` computes this by hash, not by
 *   `subscriptionKey` membership (Task 11 review round 2): a row whose own response entry
 *   `buildUpserts` dropped as a duplicate is not in [upserts] at all, yet can still be exactly
 *   the row blocking another entry's target hash — computing this list from [upserts]'s keys
 *   alone misses it. See [SubscriptionDao.applySync]'s KDoc for the write-ordering reasoning
 *   this list is part of.
 */
internal data class SyncChangeSet(
    val subscriptionId: Long,
    val groupId: Long,
    val fetchedAt: Long,
    val directives: List<SubscriptionDirectiveEntity>,
    val groupName: String?,
    val upserts: List<ProfileEntity>,
    val deleteIds: List<Long>,
    val flagIds: List<Long>,
    val clearIds: List<Long>,
    /** Null for a server-bearing success; `NoServers` for an empty body that still landed metadata. */
    val fetchStatus: String? = null,
    /** Closed, redacted diagnostic vocabulary for [fetchStatus]. */
    val fetchDetail: String? = null,
)

/** Data access for the three subscription tables. */
@Suppress("TooManyFunctions") // A DAO's surface is the width of its tables' usage, per ProfileDao.
@Dao
internal interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun subscription(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url")
    suspend fun subscriptionByUrl(url: String): SubscriptionEntity?

    /**
     * The subscription owning [groupId], if one does.
     *
     * Exists so a group deletion can be routed to the subscription lifecycle
     * instead of the plain group cascade: the cascade removes rows but not the
     * active-routing setting or the `geo/sets/<id>` trees those rows owned.
     */
    @Query("SELECT * FROM subscriptions WHERE groupId = :groupId LIMIT 1")
    suspend fun subscriptionByGroup(groupId: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions")
    suspend fun allSubscriptions(): List<SubscriptionEntity>

    @Insert
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    /** Updates only the user-owned HWID preference, preserving concurrent sync columns. */
    @Query("UPDATE subscriptions SET hwidEnabled = :enabled WHERE id = :id")
    suspend fun setHwidEnabled(
        id: Long,
        enabled: Boolean,
    )

    /** Updates only the user-owned User-Agent preference, preserving concurrent sync columns. */
    @Query("UPDATE subscriptions SET userAgentOverride = :value WHERE id = :id")
    suspend fun setUserAgentOverride(
        id: Long,
        value: String?,
    )

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteSubscription(id: Long)

    /**
     * Records a fetch failure without touching any other column.
     *
     * A targeted `@Query`, not a whole-row `@Update`, deliberately (Task 11 review fix,
     * Important 3): the syncer reads the subscription row *before* the network fetch, which can
     * run for the full `subscription-request-timeout` window (5-15s). A user calling
     * [SubscriptionRepository.setHwidEnabled] or `setUserAgentOverride` mid-fetch would have that
     * edit silently reverted by a full-row `@Update` writing the syncer's stale, pre-fetch copy
     * back over it. Writing only these two columns makes that race impossible instead of merely
     * unlikely.
     */
    @Query(
        "UPDATE subscriptions SET lastAttemptedAt = :attemptedAt, lastFetchStatus = :status, " +
            "lastFetchDetail = :detail WHERE id = :id",
    )
    suspend fun recordFetchFailure(id: Long, status: String, detail: String, attemptedAt: Long)

    /** The success half of [recordFetchFailure]'s targeted-column reasoning — same race, same fix. */
    @Query(
        "UPDATE subscriptions SET lastAttemptedAt = :at, " +
            "lastFetchedAt = CASE WHEN :status IS NULL THEN :at ELSE lastFetchedAt END, " +
            "lastFetchStatus = :status, lastFetchDetail = :detail WHERE id = :id",
    )
    suspend fun recordFetchResult(id: Long, at: Long, status: String?, detail: String?)

    @Query("SELECT * FROM subscription_directives WHERE subscriptionId = :id")
    fun observeDirectives(id: Long): Flow<List<SubscriptionDirectiveEntity>>

    @Query("SELECT * FROM subscription_directives WHERE subscriptionId = :id")
    suspend fun directives(id: Long): List<SubscriptionDirectiveEntity>

    /**
     * Every subscription's value for [key], for the one caller that asks the
     * question across subscriptions rather than about one of them: the routing
     * screen, which must raise a review sheet for a `routing` directive
     * whichever provider sent it.
     */
    @Query("SELECT * FROM subscription_directives WHERE key = :key")
    fun observeDirectivesWithKey(key: String): Flow<List<SubscriptionDirectiveEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDirectives(directives: List<SubscriptionDirectiveEntity>)

    /**
     * Removes directives the provider no longer sends.
     *
     * A key vanishing from a response means the provider stopped asserting it,
     * and leaving the old value would keep applying an instruction that was
     * withdrawn. Overrides are untouched by this — a pin is the user's, not the
     * provider's, and survives (spec D3).
     */
    @Query("DELETE FROM subscription_directives WHERE subscriptionId = :id AND key NOT IN (:keep)")
    suspend fun pruneDirectives(id: Long, keep: List<String>)

    @Query("DELETE FROM subscription_directives WHERE subscriptionId = :id")
    suspend fun clearDirectives(id: Long)

    @Query("SELECT * FROM subscription_overrides WHERE subscriptionId = :id")
    fun observeOverrides(id: Long): Flow<List<SubscriptionOverrideEntity>>

    @Query("SELECT * FROM subscription_overrides WHERE subscriptionId = :id")
    suspend fun overrides(id: Long): List<SubscriptionOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOverride(override: SubscriptionOverrideEntity)

    @Query("DELETE FROM subscription_overrides WHERE subscriptionId = :id AND key = :key")
    suspend fun deleteOverride(id: Long, key: String)

    /**
     * Every subscription-derived row in [groupId] — the set `SubscriptionSyncer.reconcile`
     * diffs the response against.
     *
     * **Known limitation** (Task 11 review round 2): `subscriptionKey IS NOT NULL` is how a
     * row identifies as subscription-managed at all. `ProfileRepository.move`'s KDoc notes that
     * a hand-imported row (`subscriptionKey = NULL`) can be moved into a subscription-sourced
     * group; such a row is invisible to this query and therefore never reconciled — never
     * matched, never deleted, never protected from an identityHash collision the way a kept
     * row is. `SubscriptionSyncer`'s `ReconciliationConflict` backstop keeps that collision from
     * crashing the sync, but the row itself sits outside spec §6.5's reconciliation table for as
     * long as it stays in that group.
     */
    @Query("SELECT * FROM profiles WHERE groupId = :groupId AND subscriptionKey IS NOT NULL")
    suspend fun subscriptionProfiles(groupId: Long): List<ProfileEntity>

    /** Overwrites [groupId]'s display name. Used for the `profile-title` directive (§A.3.2). */
    @Query("UPDATE profile_groups SET name = :name WHERE id = :groupId")
    suspend fun renameGroup(groupId: Long, name: String)

    /**
     * Deletes a single profile by id.
     *
     * A second `profiles` delete-by-id, alongside [ProfileDao.deleteProfile]: kept here rather
     * than reached through the other DAO because [applySync]'s `@Transaction` needs every
     * statement it calls to live on `this` DAO (Room resolves a `@Transaction` default method's
     * calls through its own generated implementation).
     */
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    /**
     * Sets spec D4's "kept and flagged" marker (Task 11 review fix, Important 4).
     *
     * [applySync] calls this only for a row whose flag is not already set, so the timestamp
     * recorded is the *first* sync that found the row active-but-dropped, not the most recent
     * one — "no longer offered since Aug 5" rather than a value that silently advances on every
     * refresh while the row stays dropped.
     */
    @Query("UPDATE profiles SET droppedFromSubscriptionAt = :at WHERE id = :id")
    suspend fun flagDroppedFromSubscription(id: Long, at: Long)

    /** Looks up a profile by its (groupId, subscriptionKey) slot — see [ProfileEntity]'s unique index. */
    @Query("SELECT * FROM profiles WHERE groupId = :groupId AND subscriptionKey = :subscriptionKey")
    suspend fun findBySubscriptionKey(groupId: Long, subscriptionKey: String): ProfileEntity?

    /**
     * Neutralises [id]'s `identityHash` to a placeholder no real hash can ever equal.
     *
     * See [upsertBySubscriptionKey]'s KDoc for what this is for: it is called on every row
     * about to be updated, for every row in one sync's batch, **before** any of them receives
     * its final `identityHash` — never as part of a single row's own upsert.
     */
    @Query("UPDATE profiles SET identityHash = '$TEMP_IDENTITY_HASH_PREFIX' || id WHERE id = :id")
    suspend fun clearIdentityHash(id: Long)

    /**
     * Inserts a subscription-derived profile.
     *
     * Plain `ABORT` (Room's default), matching [ProfileDao]'s own insert — **not** `REPLACE`
     * (Task 11 review fix, Critical 1). An earlier version of this method used `REPLACE` on
     * `(groupId, identityHash)` conflict to absorb spec §4.3's documented duplicate-outbound
     * collision, reasoning that `ABORT` would roll back the whole sync over one provider
     * sending a duplicate. That reasoning missed a sharper case: a *kept, active* row (spec D4)
     * is never in [applySync]'s `upserts` or `deleteIds` — it is untouched, holding its real
     * `identityHash` — so a brand-new entry whose outbound happens to collide with it hit
     * `REPLACE` and silently deleted the row a live tunnel was using, while `applySync` still
     * reported `keptActive = 1`. `SubscriptionSyncer.buildUpserts` now resolves every known
     * duplicate-outbound case — both within-batch and against a kept row — in Kotlin *before*
     * this method ever runs, using [findBySubscriptionKey]-derived and kept-row hashes it
     * already has in hand, and counts what it drops. `ABORT` stays as the backstop for a
     * collision *nothing* upstream accounted for: it must fail loudly rather than silently
     * destroy a row, exactly why [ProfileRepositoryTest] pins `ABORT` on [ProfileDao.insertProfile]
     * twice — and `SubscriptionSyncer.reconcile`'s `SyncResult.ReconciliationConflict` catch is
     * what keeps that loud failure from becoming an uncaught crash (Task 11 review round 2).
     *
     * **Known bounded limitation, recorded rather than fixed** (Task 11 review round 2): when
     * `buildUpserts` drops a profile as a duplicate (the *losing* entry — whichever response
     * entry did not claim the identity slot first), the row backing that entry's own
     * `subscriptionKey` is left holding whatever it stored from the last sync that *did* write
     * it, not the newly-parsed content. If the same two response entries keep colliding on every
     * subsequent sync, that row is never updated and never imported — see
     * `SyncResult.Synced.duplicatesDropped`'s KDoc for the self-heal condition.
     */
    @Insert
    suspend fun insertSubscriptionProfile(profile: ProfileEntity): Long

    /** The update half of [insertSubscriptionProfile]'s `ABORT` reasoning — same index, same trade-off. */
    @Update
    suspend fun updateSubscriptionProfile(profile: ProfileEntity)

    /**
     * Inserts a subscription-derived profile, or overwrites the existing row's data in place if
     * one already occupies the same (groupId, subscriptionKey) slot — see [ProfileEntity]'s
     * unique index and spec D8: refresh keys on `subscriptionKey`, never `identityHash`, so this
     * mirrors [ProfileDao.upsertProfile]'s structure with the lookup key swapped.
     *
     * Looking up by the **same** column this writes back unchanged is what makes this call, on
     * its own, incapable of ever moving a `subscriptionKey` onto a row another row still holds:
     * the row this matches is by construction the one row already carrying [profile]'s
     * `subscriptionKey`, so the update never assigns a `subscriptionKey` value that isn't already
     * sitting in that exact slot.
     *
     * That leaves the *other* unique index, `(groupId, identityHash)` — see [applySync]'s KDoc
     * for how [SyncChangeSet.clearIds] neutralises every row that could collide with this call's
     * write *before* any of [applySync]'s upserts run.
     *
     * On the update branch, [ProfileEntity.lastConnectedAt], [ProfileEntity.lastError] and
     * [ProfileEntity.createdAt] are carried over from the existing row, same reasoning as
     * [ProfileDao.upsertProfile]: [profile] is freshly built from what the subscription says
     * *now*, and a plain overwrite would reset "Last used" on every refresh.
     * [ProfileEntity.droppedFromSubscriptionAt] is deliberately **not** carried over: [profile]
     * reappearing in a response at all — which is the only way this branch runs for a
     * previously-flagged row — means the provider is offering it again, so the flag clears.
     * `SubscriptionSyncerTest.theFlagClearsWhenTheServerReappears` pins this half of the
     * lifecycle; until Task 11 review round 2 nothing did.
     *
     * [ProfileEntity.passthroughRejection] is carried over **only when [ProfileEntity.rawJson]
     * is unchanged AND the stored verdict is [PassthroughRejection.CoreRejected]** (correction
     * pass on final review C1, which carried over any stored verdict). `SubscriptionSyncer
     * .buildUpserts` never writes `CoreRejected` — only the structural verdict — so a plain
     * overwrite would silently erase a recorded core rejection on every periodic refresh
     * (`RefreshScheduler`), leaving a row that claims to run as written, in the editor, for a
     * config xray-core has already refused. The verdict is a judgement about those exact bytes: if
     * the provider changed `rawJson`, the old verdict describes a config that no longer exists and
     * must not survive onto the new one.
     *
     * Narrowed to `CoreRejected` specifically, rather than "any stored verdict", for the reason
     * the paragraph above already gives: `buildUpserts` *does* recompute the structural verdict
     * (`analysePassthrough`) on every refresh and hands it in as [profile]'s own
     * [ProfileEntity.passthroughRejection]. Carrying over an *old* structural verdict unconditionally
     * would freeze a row at whatever `analysePassthrough` said the first time it was imported —
     * an identical-bytes refresh could never re-derive a fix to that analyser (a bug, a rule
     * loosened) or a regression in it, because the branch above would keep returning the stale
     * [existing] value forever. `CoreRejected` alone needs the special case, because it is the one
     * verdict [profile]'s freshly-computed value can never independently supply.
     */
    @Transaction
    suspend fun upsertBySubscriptionKey(profile: ProfileEntity) {
        val key = requireNotNull(profile.subscriptionKey) {
            "upsertBySubscriptionKey requires a non-null subscriptionKey"
        }
        val existing = findBySubscriptionKey(profile.groupId, key)
        if (existing != null) {
            updateSubscriptionProfile(
                profile.copy(
                    id = existing.id,
                    lastConnectedAt = existing.lastConnectedAt,
                    lastError = existing.lastError,
                    createdAt = existing.createdAt,
                    passthroughRejection =
                    if (existing.rawJson == profile.rawJson &&
                        existing.passthroughRejection == PassthroughRejection.CoreRejected.name
                    ) {
                        existing.passthroughRejection
                    } else {
                        profile.passthroughRejection
                    },
                ),
            )
        } else {
            insertSubscriptionProfile(profile)
        }
    }

    /**
     * Applies one whole sync atomically (spec §6.5).
     *
     * Either the directives, the servers, the group name and the timestamp all land, or none do.
     * A half-applied subscription is the same hazard §A.3.1 describes for half-applied routing,
     * one milestone early.
     *
     * Takes a single [SyncChangeSet] rather than each field as its own parameter — partly for
     * detekt's `LongParameterList`, but mainly because it keeps `SubscriptionSyncer` from ever
     * calling the individual pieces of a sync separately: it computes the whole set first and
     * hands it over once, which is the transaction-boundary property spec §6.5 requires.
     *
     * [SyncChangeSet.subscriptionId]/[SyncChangeSet.groupId]/[SyncChangeSet.fetchedAt] are taken
     * as bare values rather than a whole `SubscriptionEntity` (Task 11 review fix, Important 3):
     * the caller read the subscription row before a fetch that can run up to
     * `subscription-request-timeout` seconds, and writing that stale snapshot back with a full
     * `@Update` would clobber a `hwidEnabled`/`userAgentOverride` edit a user made mid-sync.
     * [recordFetchResult] writes only the four columns this method actually changes.
     *
     * Write ordering, and why the unique-index collision this task was flagged against is not
     * reachable:
     *
     * 1. [SyncChangeSet.deleteIds] first. A row absent from the new response is gone before any
     *    insert or update runs, so nothing later in this method can ever collide with a row that
     *    is about to disappear anyway.
     * 2. [SyncChangeSet.flagIds] next — spec D4's rows, still present, still active, just not
     *    offered this time. Flagging them is independent of the identity work below; ordering
     *    relative to it doesn't matter, only that it happens inside the same transaction.
     * 3. [clearIdentityHash] on every id in [SyncChangeSet.clearIds], before any of
     *    [SyncChangeSet.upserts] is given its final data. `SubscriptionSyncer.reconcile` computes
     *    `clearIds` as *every existing row whose current `identityHash` equals some surviving
     *    entity's target `identityHash`* — deliberately by hash, not by `subscriptionKey`
     *    membership in `upserts` (Task 11 review round 2). The straightforward version of this —
     *    clear only the rows `upserts` itself is about to update — closes the two-rows-trading-
     *    hashes case (a provider swapping two servers' names) but misses a second one: a row
     *    whose *own* response entry `buildUpserts` dropped as a duplicate is not in `upserts` at
     *    all, yet can still be sitting on the exact hash a *surviving* entry is about to claim
     *    (a provider misconfiguring two different names onto the same outbound). Computing
     *    `clearIds` from the target-hash set catches both: every row a surviving entry will
     *    write to (self-clear, harmless) and every row merely *in the way* of one (the case the
     *    key-only version missed). Clearing every id in this list to a per-row-unique placeholder
     *    before any final write means no row in this batch is ever holding a value another row in
     *    the same batch is about to be given.
     * 4. [upsertBySubscriptionKey] for every entry, now safe: each lookup by `subscriptionKey`
     *    finds either a row already neutralised in step 3 (an update) or no row at all (an
     *    insert), and neither case can collide with anything still in step-3's cleared state.
     *    [SyncChangeSet.upserts] itself is guaranteed free of both a within-batch duplicate
     *    identityHash *and* a collision against a kept (flagged) row's identityHash —
     *    `SubscriptionSyncer.buildUpserts` resolves both in Kotlin before this method is ever
     *    called, using [insertSubscriptionProfile]'s KDoc reasoning. Any identityHash collision
     *    that still reaches [insertSubscriptionProfile] or [updateSubscriptionProfile] here is
     *    therefore one neither this method, `buildUpserts`, nor step 3's `clearIds` accounted
     *    for — `ProfileRepository.move`'s manually-moved-in row is one documented way that can
     *    still happen (see [subscriptionProfiles]'s KDoc) — and `ABORT` is deliberate: it fails the
     *    whole transaction loudly rather than silently destroying whichever row lost the race.
     *    `SubscriptionSyncer.reconcile` catches the resulting `SQLiteConstraintException` and
     *    returns `SyncResult.ReconciliationConflict` rather than letting it escape `sync()`.
     */
    @Transaction
    suspend fun applySync(changeSet: SyncChangeSet) {
        with(changeSet) {
            putDirectives(directives)
            pruneDirectives(subscriptionId, directives.map { it.key })
            groupName?.let { renameGroup(groupId, it) }
            deleteIds.forEach { deleteProfileById(it) }
            flagIds.forEach { flagDroppedFromSubscription(it, fetchedAt) }
            clearIds.forEach { clearIdentityHash(it) }
            upserts.forEach { upsertBySubscriptionKey(it) }
            recordFetchResult(subscriptionId, fetchedAt, fetchStatus, fetchDetail)
        }
    }
}
