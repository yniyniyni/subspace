// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.SubscriptionDao
import art.yniyniyni.subspace.core.data.db.SubscriptionEntity
import art.yniyniyni.subspace.core.data.db.SubscriptionOverrideEntity
import art.yniyniyni.subspace.core.parser.directive.DirectiveKind
import art.yniyniyni.subspace.core.parser.directive.KindResult
import art.yniyniyni.subspace.core.parser.directive.canonicalise
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** The most recent attempt, successful or not; scheduler-only retry pacing state. */
    val lastAttemptedAt: Long? = null,
    val lastFetchStatus: String?,
    val lastFetchDetail: String?,
    /**
     * When this subscription was added. The scheduler's retry backoff needs a starting point for
     * a subscription that has never once succeeded, where [lastFetchedAt] is null and there is
     * therefore no "healthy until" moment to measure staleness from.
     */
    val createdAt: Long = 0L,
) {
    // §5.6: url is a secret, same treatment as SubscriptionEntity.toString() —
    // this type is what crosses out of :core:data, so the redaction has to
    // hold here too, not just on the row it was mapped from.
    override fun toString(): String =
        "StoredSubscription(id=$id, groupId=$groupId, url=<redacted>, " +
            "userAgentOverride=$userAgentOverride, hwidEnabled=$hwidEnabled, " +
            "lastFetchedAt=$lastFetchedAt, lastAttemptedAt=$lastAttemptedAt, lastFetchStatus=$lastFetchStatus, " +
            "lastFetchDetail=$lastFetchDetail)"
}

/**
 * The outcome of [SubscriptionRepository.add].
 *
 * @property created `false` when [id] names a subscription that was already stored — `add` is
 *   idempotent on URL. Callers that undo a failed first sync must check this before deleting;
 *   see [SubscriptionRepository.add]'s own KDoc for what happened when they could not.
 */
public data class AddedSubscription(
    val id: Long,
    val created: Boolean,
)

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
 * ARCHITECTURE.md §A.1's boolean rule applied to this value's [EffectiveValue.value]: `true` or
 * `1` enables; **any other value — including blank or absent — disables.**
 *
 * Delegates to `:core:parser`'s [DirectiveKind.Bool] canonicalisation, the exact predicate the
 * provider path is already validated against, rather than a second hand-rolled check. Task 12
 * review fix: `RefreshScheduler`'s two call sites (`dueChecks`'s `subscription-auto-update-enable`
 * check and `openRefreshEnabled`'s `subscription-auto-update-open-enable` check) used
 * `.value != "false"`, which reads an unrecognised value — including a pinned `"0"` or `"no"` —
 * as *enabled*, backwards from this rule. The provider path was never at risk: `DirectiveValidator`
 * already canonicalises every stored provider value to exactly `"true"`/`"false"` before it can
 * reach [EffectiveValue.value]. The pin path was: [SubscriptionRepository.pin] takes an
 * unvalidated `String`, and Task 15 is what gives it its first call site.
 */
public val EffectiveValue.isEnabled: Boolean get() = isDirectiveEnabled(value)

/**
 * ARCHITECTURE.md §A.1's boolean rule for a stored directive value: only `true` or `1` enables;
 * every other value, including blank or absent, disables.
 *
 * This is the single predicate UI and scheduling code share, so a pinned `"1"` cannot be shown
 * as Off while the worker treats it as enabled.
 */
public fun isDirectiveEnabled(value: String?): Boolean {
    val result = value?.let { DirectiveKind.Bool.canonicalise(it) } ?: return false
    return result is KindResult.Canonical && result.value == "true"
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

    /** Every stored subscription, in insertion order. Recomposes on write. */
    public fun observeSubscriptions(): Flow<List<StoredSubscription>> =
        dao.observeSubscriptions().map { rows -> rows.map { it.toStored() } }

    /**
     * Emits whenever anything that determines the next automatic refresh changes.
     *
     * A subscription row covers add/delete and the last successful fetch time; its directives and
     * overrides cover the provider and user values of `profile-update-interval` and
     * `subscription-auto-update-enable`. The app owns WorkManager, so it observes this compact
     * signal and calls `RefreshScheduler.reschedule()` rather than asking a feature module to
     * depend upstream on `:app`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun observeRefreshScheduleChanges(): Flow<Unit> =
        dao.observeSubscriptions().flatMapLatest { rows ->
            if (rows.isEmpty()) {
                flowOf(Unit)
            } else {
                combine(
                    rows.map { subscription ->
                        combine(
                            dao.observeDirectives(subscription.id),
                            dao.observeOverrides(subscription.id),
                        ) { _, _ -> Unit }
                    },
                ) { Unit }
            }
        }

    /**
     * Adds a subscription and the group that holds its servers.
     *
     * Idempotent on [url]: adding the same URL twice returns the existing id
     * rather than creating a second group of the same servers.
     *
     * [AddedSubscription.created] is what tells those two cases apart, and it is not decoration.
     * A caller that cleans up after a failed first sync — [ImportViewModel][
     * art.yniyniyni.subspace.feature.profiles.add.ImportViewModel] does — must delete only a row
     * it actually caused to exist. Returning a bare id let "add a URL you already have, while the
     * provider happens to be unreachable" delete that subscription along with its servers, its
     * directives and the user's pins, through the same cascade §A.1 requires for a real delete.
     *
     * HWID defaults to **on** — §A.4.1 and §A.5. Happ sends by default; Throne
     * ships it off by default and consequently breaks limit-enabled providers
     * out of the box. Do the opposite.
     */
    public suspend fun add(
        url: String,
        name: String,
    ): AddedSubscription =
        addMutex.withLock {
            dao.subscriptionByUrl(url)?.let { return@withLock AddedSubscription(it.id, created = false) }

            val now = System.currentTimeMillis()
            val groupId = profiles.createGroup(name, source = GROUP_SOURCE_SUBSCRIPTION)
            dao.insertSubscription(
                SubscriptionEntity(
                    groupId = groupId,
                    url = url,
                    userAgentOverride = null,
                    hwidEnabled = true,
                    lastFetchedAt = null,
                    lastAttemptedAt = null,
                    lastFetchStatus = null,
                    lastFetchDetail = null,
                    createdAt = now,
                ),
            ).let { AddedSubscription(it, created = true) }
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

    /** Toggles the HWID header (§A.4.1) for [id]. A no-op if the subscription no longer exists. */
    public suspend fun setHwidEnabled(
        id: Long,
        enabled: Boolean,
    ) {
        val existing = dao.subscription(id) ?: return
        dao.updateSubscription(existing.copy(hwidEnabled = enabled))
    }

    /** Sets a per-subscription User-Agent override, or clears it when [userAgent] is null or blank. */
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
        lastAttemptedAt = lastAttemptedAt,
        lastFetchStatus = lastFetchStatus,
        lastFetchDetail = lastFetchDetail,
        createdAt = createdAt,
    )
