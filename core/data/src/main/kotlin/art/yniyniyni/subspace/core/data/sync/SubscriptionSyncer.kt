// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.sync

import android.util.Log
import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.ProfileRepository
import art.yniyniyni.subspace.core.data.SubscriptionRepository
import art.yniyniyni.subspace.core.data.db.ProfileEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionDao
import art.yniyniyni.subspace.core.data.db.SubscriptionDirectiveEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionEntity
import art.yniyniyni.subspace.core.data.protocolName
import art.yniyniyni.subspace.core.data.serialization.identityHashOf
import art.yniyniyni.subspace.core.data.serialization.identityHashOfRaw
import art.yniyniyni.subspace.core.data.serialization.toJson
import art.yniyniyni.subspace.core.data.transportSummary
import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.network.FetchFailure
import art.yniyniyni.subspace.core.network.FetchOutcome
import art.yniyniyni.subspace.core.network.SubscriptionRequest
import art.yniyniyni.subspace.core.network.SubscriptionSource
import art.yniyniyni.subspace.core.parser.ParseFailure
import art.yniyniyni.subspace.core.parser.SubscriptionParser
import art.yniyniyni.subspace.core.parser.directive.DirectiveSplitter
import art.yniyniyni.subspace.core.parser.directive.DirectiveValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SubscriptionSyncer"
private const val DEFAULT_TIMEOUT_SECONDS = 9
private const val KEY_TIMEOUT = "subscription-request-timeout"
private const val KEY_TITLE = "profile-title"
private const val KEY_USER_AGENT = "change-user-agent"

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
 */
@Singleton
public class SubscriptionSyncer
@Inject
internal constructor(
    private val dao: SubscriptionDao,
    private val subscriptions: SubscriptionRepository,
    // Not read by this class: the persist stage writes through [dao] directly so the whole
    // sync lands in [SubscriptionDao.applySync]'s one transaction, which ProfileRepository's
    // individual (non-transactional) writes cannot provide. Kept in the constructor because
    // it is part of this class's public dependency shape — Part 2's worker and UI callers
    // construct a [SubscriptionSyncer] the same way they reach every other repository, and
    // Task 11's test constructs it with one explicitly.
    private val profiles: ProfileRepository,
    private val source: SubscriptionSource,
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
                SyncResult.Failed(FetchFailure.NotFound)
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
                    hwidEnabled = subscription.hwidEnabled,
                    userAgentOverride = userAgent,
                    timeoutSeconds = timeout,
                ),
            )

        return when (outcome) {
            is FetchOutcome.Failed -> recordFailure(subscription, outcome.reason)
            is FetchOutcome.Success -> applySuccess(subscription, outcome, activeProfileId)
        }
    }

    /**
     * Failure short-circuit (spec §6, step 1). Writes only the status columns
     * and touches nothing else — the stored servers stay exactly as they were.
     */
    private suspend fun recordFailure(
        subscription: SubscriptionEntity,
        reason: FetchFailure,
    ): SyncResult {
        dao.updateSubscription(subscription.copy(lastFetchStatus = reason.name, lastFetchDetail = reason.name))
        return SyncResult.Failed(reason)
    }

    private suspend fun applySuccess(
        subscription: SubscriptionEntity,
        outcome: FetchOutcome.Success,
        activeProfileId: Long?,
    ): SyncResult {
        val split = DirectiveSplitter.split(outcome.headers, outcome.body)
        val validated = DirectiveValidator.validate(split.directives)
        val parsed = SubscriptionParser.parse(split.remainingBody)

        val now = System.currentTimeMillis()
        val directives =
            validated.accepted.map { (key, value) -> SubscriptionDirectiveEntity(subscription.id, key, value, now) }
        val groupName = validated.accepted[KEY_TITLE]
        val refreshed = subscription.copy(lastFetchedAt = now, lastFetchStatus = null, lastFetchDetail = null)

        return if (parsed.profiles.isEmpty()) {
            // The provider's metadata is still valid even when its server list
            // is not, so directives (and a title rename) still land — only the
            // server set is left untouched.
            dao.applySync(refreshed, directives, groupName, emptyList(), emptyList())
            SyncResult.NoServers(parsed.failures.redactedDetail())
        } else {
            val response =
                ParsedResponse(refreshed, directives, groupName, parsed.profiles, validated.rejections.size, now)
            reconcile(response, activeProfileId)
        }
    }

    /** Spec §6.5's reconciliation table, computed here and handed to [SubscriptionDao.applySync] as one change set. */
    private suspend fun reconcile(
        response: ParsedResponse,
        activeProfileId: Long?,
    ): SyncResult.Synced {
        val groupId = response.subscription.groupId
        val keys = subscriptionKeysFor(response.parsedProfiles.map { it.name })
        val keySet = keys.toSet()
        val existingRows = dao.subscriptionProfiles(groupId)
        val existingKeys = existingRows.mapNotNull { it.subscriptionKey }.toSet()

        val dropped = existingRows.filter { it.subscriptionKey !in keySet }
        val keptActive = dropped.count { it.id == activeProfileId }
        val deleteIds = dropped.filter { it.id != activeProfileId }.map { it.id }

        val upserts = buildUpserts(response.parsedProfiles, keys, groupId, response.now)
        val added = keys.count { it !in existingKeys }

        dao.applySync(response.subscription, response.directives, response.groupName, upserts, deleteIds)
        logCountDiscrepancy(groupId, keySet)

        return SyncResult.Synced(
            added = added,
            updated = keys.size - added,
            removed = deleteIds.size,
            keptActive = keptActive,
            rejectedDirectives = response.rejectedDirectives,
        )
    }

    /**
     * Spec §4.3's documented, bounded limitation made diagnosable: two servers
     * in one response with byte-identical outbounds but different names
     * collide on `(groupId, identityHash)`, so [SubscriptionDao.applySync] can
     * silently write fewer rows than this sync parsed (see
     * [art.yniyniyni.subspace.core.data.db.SubscriptionDao.insertSubscriptionProfile]'s
     * KDoc for why that is `REPLACE` rather than a thrown exception). Counts
     * only — never a name, never an address (§5.6).
     */
    private suspend fun logCountDiscrepancy(
        groupId: Long,
        keys: Set<String>,
    ) {
        val written = dao.subscriptionProfiles(groupId).count { it.subscriptionKey in keys }
        if (written != keys.size) {
            Log.w(TAG, "sync wrote $written server(s) for ${keys.size} parsed — see spec §4.3")
        }
    }
}

/**
 * One successful fetch's parsed, validated output, bundled so [SubscriptionSyncer.reconcile]
 * stays under detekt's `LongParameterList` threshold instead of taking each field separately.
 */
private data class ParsedResponse(
    val subscription: SubscriptionEntity,
    val directives: List<SubscriptionDirectiveEntity>,
    val groupName: String?,
    val parsedProfiles: List<Profile>,
    val rejectedDirectives: Int,
    val now: Long,
)

/**
 * Builds this sync's write set, one [ProfileEntity] per parsed profile, in response order.
 *
 * Identity mirrors [ProfileRepository.import]'s: a profile with no [Profile.rawJson] is `TYPED`
 * and identified by its outbound; one with `rawJson` unique to it within this batch is `RAW_JSON`
 * and identified by those exact bytes; one whose `rawJson` is shared by several profiles (one raw
 * element fanning out into several outbounds) falls back to outbound identity, same as `import`.
 */
private fun buildUpserts(
    profiles: List<Profile>,
    keys: List<String>,
    groupId: Long,
    now: Long,
): List<ProfileEntity> {
    val rawJsonFanoutCounts = profiles.mapNotNull { it.rawJson }.groupingBy { it }.eachCount()
    return profiles.mapIndexed { index, profile ->
        val rawJson = profile.rawJson
        val kind = if (rawJson == null) ProfileKind.TYPED else ProfileKind.RAW_JSON
        val identityHash =
            if (rawJson != null && rawJsonFanoutCounts.getValue(rawJson) == 1) {
                identityHashOfRaw(rawJson)
            } else {
                identityHashOf(profile.outbound, kind)
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
}

/** The first failure's redacted reason (§5.6) — never the body. [ParseFailure]'s fields are closed vocabulary. */
private fun List<ParseFailure>.redactedDetail(): String =
    firstOrNull()?.let { "${it.reason}: ${it.detail}" } ?: "unknown"
