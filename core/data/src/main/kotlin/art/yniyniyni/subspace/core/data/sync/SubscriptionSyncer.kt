// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data.sync

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.db.ProfileEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionDao
import art.yniyniyni.subspace.core.data.db.SubscriptionDirectiveEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionEntity
import art.yniyniyni.subspace.core.data.db.SyncChangeSet
import art.yniyniyni.subspace.core.data.protocolName
import art.yniyniyni.subspace.core.data.serialization.identityHashOf
import art.yniyniyni.subspace.core.data.serialization.identityHashOfRaw
import art.yniyniyni.subspace.core.data.serialization.toJson
import art.yniyniyni.subspace.core.data.transportSummary
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.TunnelProxyLocator
import art.yniyniyni.subspace.core.network.FetchFailure
import art.yniyniyni.subspace.core.network.FetchOutcome
import art.yniyniyni.subspace.core.network.SubscriptionRequest
import art.yniyniyni.subspace.core.network.SubscriptionSource
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import art.yniyniyni.subspace.core.parser.directive.DirectiveSplitter
import art.yniyniyni.subspace.core.parser.directive.DirectiveValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SubscriptionSyncer"
private const val DEFAULT_TIMEOUT_SECONDS = 9
private const val KEY_TIMEOUT = "subscription-request-timeout"
private const val KEY_TITLE = "profile-title"
private const val KEY_USER_AGENT = "change-user-agent"
private const val NO_SERVERS_STATUS = "NoServers"
private const val MAX_LOG_KEY_LENGTH = 64

/**
 * ARCHITECTURE.md §A.1's pipeline, end to end: fetch → split → validate →
 * parse → reconcile → persist, with the persist stage landing in one
 * [SubscriptionDao.applySync] transaction (spec §6.5).
 *
 * Lives in `:core:data` rather than `:feature:profiles` because the UI and the
 * background worker must call the same code — a worker reaching into a
 * feature module's internals to sync is the shape that makes the two paths
 * drift.
 *
 * Entirely on [Dispatchers.IO] (§5.3).
 *
 * [proxyLocator] is how a subscription refresh reaches the tunnel's loopback
 * HTTP proxy (spec §5.4): `:app` supplies the only implementation, over
 * `TunnelClient`'s published state, because `:core:data` cannot depend on
 * `:service` (§4). A background refresh with no bound service — or no tunnel
 * up at all — gets null back and fetches directly; that is correct fallback
 * behaviour, not a failure (see [TunnelProxyLocator.httpProxyPortOrNull]).
 */
@Singleton
public class SubscriptionSyncer
@Inject
internal constructor(
    private val dao: SubscriptionDao,
    private val subscriptions: SubscriptionRepository,
    private val settings: SettingsRepository,
    private val source: SubscriptionSource,
    private val proxyLocator: TunnelProxyLocator,
) {
    /**
     * Runs one sync of [subscriptionId].
     *
     * @param activeProfileId the profile the tunnel is currently using, if
     *   any. A server the provider has dropped is kept and flagged rather
     *   than deleted when it matches (spec D4) — deleting it would leave
     *   §5.5's single source of truth holding a `profileId` that no longer
     *   resolves.
     */
    public suspend fun sync(
        subscriptionId: Long,
        activeProfileId: Long? = null,
    ): SyncResult =
        withContext(Dispatchers.IO) {
            val subscription = dao.subscription(subscriptionId)
            if (subscription == null) {
                SyncResult.Failed(SubscriptionSyncFailure.NotFound)
            } else {
                syncExisting(subscription, activeProfileId)
            }
        }

    private suspend fun syncExisting(
        subscription: SubscriptionEntity,
        activeProfileId: Long?,
    ): SyncResult {
        // subscription-request-timeout is a directive delivered *by* the fetch
        // it configures, so it comes from the previous fetch's stored value —
        // the default only on first contact.
        val timeout =
            subscriptions.effective(subscription.id, KEY_TIMEOUT, null).value?.toIntOrNull()
                ?: DEFAULT_TIMEOUT_SECONDS
        val userAgent =
            subscription.userAgentOverride
                ?: subscriptions.effective(subscription.id, KEY_USER_AGENT, null).value

        val outcome =
            source.fetch(
                SubscriptionRequest(
                    url = subscription.url,
                    hwidEnabled = subscription.hwidEnabled && settings.hwidEnabled.first(),
                    userAgentOverride = userAgent,
                    timeoutSeconds = timeout,
                    proxyPort = proxyLocator.httpProxyPortOrNull(),
                ),
            )

        return when (outcome) {
            is FetchOutcome.Failed -> recordFailure(subscription.id, outcome.reason, outcome.detail)
            is FetchOutcome.Success -> applySuccess(subscription, outcome, activeProfileId)
        }
    }

    /**
     * Failure short-circuit (spec §6, step 1). Writes only the status columns via
     * [SubscriptionDao.recordFetchFailure] — never a whole-row `@Update` — and touches nothing
     * else, so the stored servers stay exactly as they were and a concurrent
     * `setHwidEnabled`/`setUserAgentOverride` edit is never clobbered (Task 11 review fix,
     * Important 3).
     */
    private suspend fun recordFailure(
        subscriptionId: Long,
        reason: FetchFailure,
        detail: String?,
    ): SyncResult {
        // The stored status column stays FetchFailure.name — SubscriptionEntity's own storage,
        // internal to :core:data, is unaffected by the SyncResult.Failed boundary translation
        // below (SubscriptionSyncFailure's own KDoc explains why that exists).
        //
        // The detail column used to be a second copy of that same name, carrying no information
        // at all. It now holds the cause when the fetcher knows one — see
        // FetchOutcome.Failed.detail, which also explains why that value is safe to store and to
        // log while the exception's message is not. It falls back to the status name only when
        // the failure came from a response rather than a throwable (a 404, a 500), where there is
        // no cause to name beyond the status itself.
        val stored = detail ?: reason.name
        dao.recordFetchFailure(subscriptionId, reason.name, stored, System.currentTimeMillis())
        // Deliberately W, and deliberately only the two closed-vocabulary names: no URL, no host,
        // no body. This is the line whose absence made M4's device run need four out-of-app
        // probes to tell one network failure from another.
        Log.w(TAG, "subscription $subscriptionId fetch failed reason=${reason.name} cause=$stored")
        return SyncResult.Failed(reason.toSyncFailure())
    }

    private suspend fun applySuccess(
        subscription: SubscriptionEntity,
        outcome: FetchOutcome.Success,
        activeProfileId: Long?,
    ): SyncResult {
        val split = DirectiveSplitter.split(outcome.headers, outcome.body)
        val validated = DirectiveValidator.validate(split.directives)
        validated.rejections.forEach { rejection ->
            // Values are hostile subscription contents and are structurally absent from
            // DirectiveRejection. Restrict even an unknown key to header-name characters before
            // it reaches logcat: it remains diagnosable without becoming a log-injection path.
            Log.w(TAG, "rejected directive key=${rejection.key.logKey()} reason=${rejection.reason}")
        }
        val parsed = SubscriptionParser.parse(split.remainingBody)

        val now = System.currentTimeMillis()
        val directives =
            validated.accepted.map { (key, value) -> SubscriptionDirectiveEntity(subscription.id, key, value, now) }
        val groupName = validated.accepted[KEY_TITLE]

        return if (parsed.profiles.isEmpty()) {
            // The provider's metadata is still valid even when its server list
            // is not, so directives (and a title rename) still land — only the
            // server set is left untouched.
            dao.applySync(
                SyncChangeSet(
                    subscriptionId = subscription.id,
                    groupId = subscription.groupId,
                    fetchedAt = now,
                    directives = directives,
                    groupName = groupName,
                    upserts = emptyList(),
                    deleteIds = emptyList(),
                    flagIds = emptyList(),
                    clearIds = emptyList(),
                    fetchStatus = NO_SERVERS_STATUS,
                    fetchDetail = NO_SERVERS_STATUS,
                ),
            )
            SyncResult.NoServers(parsed.failures.redactedDetail())
        } else {
            val response =
                ParsedResponse(
                    subscriptionId = subscription.id,
                    groupId = subscription.groupId,
                    directives = directives,
                    groupName = groupName,
                    parsedProfiles = parsed.profiles,
                    rejectedDirectives = validated.rejections.size,
                    now = now,
                )
            // Fetching can take the full provider timeout. Resolve the active tunnel only
            // immediately before reconciliation so a user switching profiles during that wait
            // cannot cause the newly active, provider-absent row to be deleted (spec D4).
            reconcile(response, activeProfileId ?: settings.activeProfileId.first())
        }
    }

    /**
     * Spec §6.5's reconciliation table, computed here and handed to [SubscriptionDao.applySync]
     * as one change set.
     *
     * Returns [SyncResult.ReconciliationConflict] instead of [SyncResult.Synced] if
     * [SubscriptionDao.applySync] throws [SQLiteConstraintException] — an identity collision
     * neither [buildUpserts] nor [clearIds] below accounted for (Task 11 review round 2's
     * backstop; `ProfileRepository.move`'s KDoc documents one way that can still happen). The
     * whole transaction rolls back on any thrown exception, so this is a clean "nothing landed"
     * failure, never a partial write — and the exception's own message is never surfaced,
     * because it can quote this table's column values (§5.6), the same hazard
     * `ProfileRepository.move`'s existing catch guards against for the same exception type.
     */
    private suspend fun reconcile(
        response: ParsedResponse,
        activeProfileId: Long?,
    ): SyncResult {
        val groupId = response.groupId
        val keys = subscriptionKeysFor(response.parsedProfiles.map { it.name })
        val keySet = keys.toSet()
        val existingRows = dao.subscriptionProfiles(groupId)
        val existingKeys = existingRows.mapNotNull { it.subscriptionKey }.toSet()

        // Rows the new response no longer names. Split into the ordinary case
        // (delete) and spec D4's case (the row is the running tunnel's — keep
        // it, flag it, never let it collide with anything new).
        val absent = existingRows.filter { it.subscriptionKey !in keySet }
        val keptRows = absent.filter { it.id == activeProfileId }
        val deleteIds = absent.filter { it.id != activeProfileId }.map { it.id }
        // Only rows not already flagged: see flagDroppedFromSubscription's KDoc
        // for why this preserves the *first* drop time rather than sliding it
        // forward on every refresh the row stays dropped.
        val flagIds = keptRows.filter { it.droppedFromSubscriptionAt == null }.map { it.id }

        // A kept row is untouched by this sync — not in upserts, not deleted —
        // so it keeps its real identityHash. buildUpserts must not hand SQLite
        // a new entry claiming that same hash (Task 11 review fix, Critical 1).
        val protectedHashes = keptRows.map { it.identityHash }.toSet()
        val built = buildUpserts(response.parsedProfiles, keys, groupId, response.now, protectedHashes)

        // Counts reflect what buildUpserts actually kept, not what the response
        // parsed to (Task 11 review fix, Important 2) — a duplicate-outbound
        // entry buildUpserts dropped never counts as added or updated.
        val writtenKeys = built.entities.mapNotNull { it.subscriptionKey }.toSet()
        val added = writtenKeys.count { it !in existingKeys }
        val updated = writtenKeys.size - added

        // Every existing row currently holding a hash some surviving entity is about to write —
        // by hash, not by subscriptionKey membership in built.entities (Task 11 review round 2).
        // The key-only version closes the two-rows-trading-hashes case (a provider swapping two
        // servers' names: both rows are in upserts, both get cleared) but misses a second one: a
        // row whose own response entry buildUpserts dropped as a duplicate is not in upserts at
        // all, yet can still be sitting on the exact hash a surviving entry now claims (a
        // provider misconfiguring two different names onto the same outbound). See
        // SubscriptionDao.applySync's KDoc, step 3, for the full reasoning.
        val targetHashes = built.entities.map { it.identityHash }.toSet()
        val clearIds = existingRows.filter { it.identityHash in targetHashes }.map { it.id }

        val changeSet =
            SyncChangeSet(
                subscriptionId = response.subscriptionId,
                groupId = groupId,
                fetchedAt = response.now,
                directives = response.directives,
                groupName = response.groupName,
                upserts = built.entities,
                deleteIds = deleteIds,
                flagIds = flagIds,
                clearIds = clearIds,
            )

        return try {
            dao.applySync(changeSet)

            if (built.duplicatesDropped > 0) {
                // Counts only — never a name, never an address (§5.6).
                Log.w(TAG, "sync dropped ${built.duplicatesDropped} duplicate-outbound server(s) — see spec §4.3")
            }

            SyncResult.Synced(
                added = added,
                updated = updated,
                removed = deleteIds.size,
                keptActive = keptRows.size,
                rejectedDirectives = response.rejectedDirectives,
                duplicatesDropped = built.duplicatesDropped,
            )
        } catch (ignored: SQLiteConstraintException) {
            SyncResult.ReconciliationConflict("identity collision during reconciliation")
        }
    }
}

/**
 * One successful fetch's parsed, validated output, bundled so [SubscriptionSyncer.reconcile]
 * stays under detekt's `LongParameterList` threshold instead of taking each field separately.
 */
private data class ParsedResponse(
    val subscriptionId: Long,
    val groupId: Long,
    val directives: List<SubscriptionDirectiveEntity>,
    val groupName: String?,
    val parsedProfiles: List<Profile>,
    val rejectedDirectives: Int,
    val now: Long,
)

/** [buildUpserts]'s output: the write set, plus how many parsed profiles it declined to include. */
private data class UpsertBuildResult(
    val entities: List<ProfileEntity>,
    val duplicatesDropped: Int,
)

/**
 * Builds this sync's write set, one [ProfileEntity] per parsed profile that does not collide, in
 * response order.
 *
 * Identity mirrors [art.yniyniyni.subspace.core.data.ProfileRepository.import]'s: a profile with
 * no [Profile.rawJson] is `TYPED` and identified by its outbound; one with `rawJson` unique to it
 * within this batch is `RAW_JSON` and identified by those exact bytes; one whose `rawJson` is
 * shared by several profiles (one raw element fanning out into several outbounds) falls back to
 * outbound identity, same as `import`.
 *
 * Resolves spec §4.3's documented, bounded duplicate-outbound collision **before** any DB write
 * (Task 11 review fix, Critical 1 and Important 2): a computed `identityHash` already claimed —
 * by an earlier entry in this same batch, or by [protectedHashes] (spec D4's kept-active rows,
 * which this sync never touches and must never let a new entry silently displace) — drops that
 * profile instead of reaching [SubscriptionDao.insertSubscriptionProfile]/`updateSubscriptionProfile`,
 * where an `ABORT` conflict would fail the whole transaction and a `REPLACE` one would silently
 * delete whichever row lost the race. The caller counts and logs what this drops instead of
 * inferring it from a post-write re-read.
 */
private fun buildUpserts(
    profiles: List<Profile>,
    keys: List<String>,
    groupId: Long,
    now: Long,
    protectedHashes: Set<String>,
): UpsertBuildResult {
    val rawJsonFanoutCounts = profiles.mapNotNull { it.rawJson }.groupingBy { it }.eachCount()
    val claimedHashes = protectedHashes.toMutableSet()
    var duplicatesDropped = 0

    val entities =
        profiles.mapIndexedNotNull { index, profile ->
            val rawJson = profile.rawJson
            val kind = if (rawJson == null) ProfileKind.TYPED else ProfileKind.RAW_JSON
            val identityHash =
                if (rawJson != null && rawJsonFanoutCounts.getValue(rawJson) == 1) {
                    identityHashOfRaw(rawJson)
                } else {
                    identityHashOf(profile.outbound, kind)
                }

            if (!claimedHashes.add(identityHash)) {
                duplicatesDropped++
                return@mapIndexedNotNull null
            }

            ProfileEntity(
                groupId = groupId,
                kind = kind.name,
                identityHash = identityHash,
                name = profile.name,
                protocol = profile.outbound.protocolName(),
                address = profile.outbound.address,
                port = profile.outbound.port,
                transport = profile.outbound.transportSummary(),
                outbound = profile.outbound.toJson(),
                rawJson = rawJson,
                position = index,
                lastConnectedAt = null,
                lastError = null,
                createdAt = now,
                subscriptionKey = keys[index],
            )
        }

    return UpsertBuildResult(entities, duplicatesDropped)
}

/** The first failure's redacted reason (§5.6) — never the body. [ParseFailure]'s fields are closed vocabulary. */
private fun List<ParseFailure>.redactedDetail(): String =
    firstOrNull()?.let { "${it.reason}: ${it.detail}" } ?: "unknown"

/** A bounded, value-free diagnostic representation of a hostile directive key. */
private fun String.logKey(): String =
    filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_LOG_KEY_LENGTH)
