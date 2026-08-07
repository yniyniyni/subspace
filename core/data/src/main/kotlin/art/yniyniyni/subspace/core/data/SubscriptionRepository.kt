// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.SubscriptionDao
import art.yniyniyni.subspace.core.data.db.SubscriptionEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionOverrideEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val GROUP_SOURCE_SUBSCRIPTION = "SUBSCRIPTION"

/**
 * One stored subscription, mapped out for consumers outside `:core:data`.
 *
 * [url] is a secret (§5.6): never log it, and redact it for display.
 */
public data class StoredSubscription(
    val id: Long,
    val groupId: Long,
    val url: String,
    val userAgentOverride: String?,
    val hwidEnabled: Boolean,
    val lastFetchedAt: Long?,
    val lastFetchStatus: String?,
    val lastFetchDetail: String?,
) {
    // §5.6: url is a secret, same treatment as SubscriptionEntity.toString() —
    // this type is what crosses out of :core:data, so the redaction has to
    // hold here too, not just on the row it was mapped from.
    override fun toString(): String =
        "StoredSubscription(id=$id, groupId=$groupId, url=<redacted>, " +
            "userAgentOverride=$userAgentOverride, hwidEnabled=$hwidEnabled, " +
            "lastFetchedAt=$lastFetchedAt, lastFetchStatus=$lastFetchStatus, " +
            "lastFetchDetail=$lastFetchDetail)"
}

/**
 * A resolved setting, with enough context for the UI to explain itself.
 *
 * Spec D3: the provider wins by default, a pin survives every update. Both
 * values are carried, not just the winner, because a pinned field that shows
 * only its pinned value is indistinguishable from a subscription that stopped
 * updating — the user needs to see *"provider suggests 6 · you pinned 24"*.
 *
 * @property value the effective value: pin, else provider, else default.
 * @property providerValue what the provider last sent, or null if it sent none.
 */
public data class EffectiveValue(
    val key: String,
    val value: String?,
    val providerValue: String?,
    val isPinned: Boolean,
) {
    // §5.6: value/providerValue carry the same DirectiveRegistry vocabulary
    // SubscriptionDirectiveEntity.toString() redacts — DirectiveKind.Url and
    // the socks/http auth password keys mean a validated, in-vocabulary value
    // can still be a URL or a plaintext credential. key stays visible, same
    // reasoning as the entities: it is closed registry vocabulary, safe to log.
    override fun toString(): String =
        "EffectiveValue(key=$key, value=<redacted>, providerValue=<redacted>, isPinned=$isPinned)"
}

/**
 * The repository `:feature:*` and the sync worker use to reach subscriptions.
 *
 * Owns the subscription-backed group: adding a subscription creates a
 * `source = "SUBSCRIPTION"` row in `profile_groups`, which is the M4 seam
 * `ProfileGroupEntity` was built with.
 */
@Singleton
public class SubscriptionRepository
@Inject
internal constructor(
    private val dao: SubscriptionDao,
    private val profiles: ProfileRepository,
) {
    // add() is a read (does this url exist?) followed by a conditional write,
    // the same shape ProfileRepository.defaultGroupId() guards — and the same
    // race, since two coroutines can both see null before either inserts and
    // the second then hits the unique index on url.
    private val addMutex = Mutex()

    public fun observeSubscriptions(): Flow<List<StoredSubscription>> =
        dao.observeSubscriptions().map { rows -> rows.map { it.toStored() } }

    /**
     * Adds a subscription and the group that holds its servers.
     *
     * Idempotent on [url]: adding the same URL twice returns the existing id
     * rather than creating a second group of the same servers.
     *
     * HWID defaults to **on** — §A.4.1 and §A.5. Happ sends by default; Throne
     * ships it off by default and consequently breaks limit-enabled providers
     * out of the box. Do the opposite.
     */
    public suspend fun add(
        url: String,
        name: String,
    ): Long =
        addMutex.withLock {
            dao.subscriptionByUrl(url)?.let { return@withLock it.id }

            val now = System.currentTimeMillis()
            val groupId = profiles.createGroup(name, source = GROUP_SOURCE_SUBSCRIPTION)
            dao.insertSubscription(
                SubscriptionEntity(
                    groupId = groupId,
                    url = url,
                    userAgentOverride = null,
                    hwidEnabled = true,
                    lastFetchedAt = null,
                    lastFetchStatus = null,
                    lastFetchDetail = null,
                    createdAt = now,
                ),
            )
        }

    /**
     * Deletes a subscription, its group, its servers, its directives and its
     * overrides.
     *
     * Implemented by deleting the **group**: §A.1 requires deletion to cascade,
     * and the foreign keys make that one statement rather than five remembered
     * ones.
     */
    public suspend fun delete(id: Long) {
        val subscription = dao.subscription(id) ?: return
        profiles.deleteGroup(subscription.groupId)
    }

    public suspend fun setHwidEnabled(
        id: Long,
        enabled: Boolean,
    ) {
        val existing = dao.subscription(id) ?: return
        dao.updateSubscription(existing.copy(hwidEnabled = enabled))
    }

    public suspend fun setUserAgentOverride(
        id: Long,
        userAgent: String?,
    ) {
        val existing = dao.subscription(id) ?: return
        dao.updateSubscription(existing.copy(userAgentOverride = userAgent?.takeIf(String::isNotBlank)))
    }

    /** Pins [value] for [key], so no future fetch moves it (spec D3). */
    public suspend fun pin(
        id: Long,
        key: String,
        value: String,
    ): Unit =
        dao.putOverride(
            SubscriptionOverrideEntity(id, key, value, System.currentTimeMillis()),
        )

    /** Removes a pin, handing [key] back to the provider. */
    public suspend fun unpin(
        id: Long,
        key: String,
    ): Unit = dao.deleteOverride(id, key)

    /** Resolves [key]: pin, else provider, else [default]. */
    public suspend fun effective(
        id: Long,
        key: String,
        default: String?,
    ): EffectiveValue {
        val providerValue = dao.directives(id).firstOrNull { it.key == key }?.value
        val pinned = dao.overrides(id).firstOrNull { it.key == key }?.value
        return resolveEffective(key, providerValue, pinned, default)
    }

    /**
     * Resolves [key] the same way [effective] does, recomposing on every
     * directive or override change for this subscription.
     */
    public fun observeEffective(
        id: Long,
        key: String,
        default: String?,
    ): Flow<EffectiveValue> =
        combine(
            dao.observeDirectives(id),
            dao.observeOverrides(id),
        ) { directives, overrides ->
            val providerValue = directives.firstOrNull { it.key == key }?.value
            val pinned = overrides.firstOrNull { it.key == key }?.value
            resolveEffective(key, providerValue, pinned, default)
        }
}

/** Spec D3's precedence, shared by [SubscriptionRepository.effective] and [SubscriptionRepository.observeEffective]. */
private fun resolveEffective(
    key: String,
    providerValue: String?,
    pinned: String?,
    default: String?,
) = EffectiveValue(
    key = key,
    value = pinned ?: providerValue ?: default,
    providerValue = providerValue,
    isPinned = pinned != null,
)

private fun SubscriptionEntity.toStored() =
    StoredSubscription(
        id = id,
        groupId = groupId,
        url = url,
        userAgentOverride = userAgentOverride,
        hwidEnabled = hwidEnabled,
        lastFetchedAt = lastFetchedAt,
        lastFetchStatus = lastFetchStatus,
        lastFetchDetail = lastFetchDetail,
    )
