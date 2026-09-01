// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One subscription, and the group it owns.
 *
 * The group is the M4 seam [ProfileGroupEntity] was built with: a
 * subscription-backed group is an ordinary row with `source = "SUBSCRIPTION"`,
 * so the Servers screen already knows how to draw one and no migration was
 * needed for that part.
 *
 * `ON DELETE CASCADE` on [groupId] is how ARCHITECTURE.md §A.1's *"deletion must
 * cascade"* rule is enforced — by the schema, rather than by remembering to do
 * it in every delete path. Deleting the group removes this row, its directives,
 * its overrides, and (via [ProfileEntity]'s own cascade) its servers.
 *
 * [url] is a secret (§5.6). It is never logged, never included in a diagnostic,
 * and redacted for display.
 *
 * [lastFetchDetail] is a closed vocabulary — a `FetchFailure` name — never a raw
 * message. The M2 residuals record argues that free-text-plus-regex redaction
 * eventually loses; this surface starts closed rather than being retrofitted.
 */
@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = ProfileGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["groupId"]),
    ],
)
internal data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val url: String,
    val userAgentOverride: String?,
    val hwidEnabled: Boolean,
    val lastFetchedAt: Long?,
    /** The most recent fetch attempt, including failures and empty responses, for retry pacing. */
    val lastAttemptedAt: Long?,
    val lastFetchStatus: String?,
    val lastFetchDetail: String?,
    val createdAt: Long,
) {
    // §5.6: url is a secret. The generated data-class toString() would print
    // it verbatim, which is exactly the "reaches a log line" failure mode
    // this class exists to avoid — a structural guard against a future
    // `Timber.d("$subscription")`, not a fix for a leak that exists today.
    // Same shape as SubscriptionRequest and FetchOutcome.Success in
    // :core:network (Task 7). The other fields are not secrets and stay
    // visible: they are what makes a logged instance useful for debugging.
    override fun toString(): String =
        "SubscriptionEntity(id=$id, groupId=$groupId, url=<redacted>, " +
            "userAgentOverride=$userAgentOverride, hwidEnabled=$hwidEnabled, " +
            "lastFetchedAt=$lastFetchedAt, lastAttemptedAt=$lastAttemptedAt, lastFetchStatus=$lastFetchStatus, " +
            "lastFetchDetail=$lastFetchDetail, createdAt=$createdAt)"
}

/**
 * What the provider last sent, **after** validation.
 *
 * §A.1: *"validate every value against a schema before it reaches storage"*.
 * Nothing unvalidated reaches this table — `DirectiveValidator` is the only
 * writer's source, and its accepted map is by construction registry keys with
 * canonicalised values.
 *
 * [key] is closed vocabulary — one of `DirectiveRegistry`'s accepted names —
 * and safe to log. [value] is not uniformly safe: `DirectiveRegistry` accepts
 * `DirectiveKind.Url` for several keys (`support-url`, `fallback-url`,
 * `sub-info-button-link`, `server-address-resolve-dns-domain`, among others)
 * and plaintext credentials for `socks-auth-password`/`http-auth-password`, so
 * a validated, in-vocabulary [value] can still be a URL or a password. §5.6's
 * "no server addresses, no config contents" bars both, so [value] gets the
 * same `toString()` treatment as [SubscriptionEntity.url].
 */
@Entity(
    tableName = "subscription_directives",
    primaryKeys = ["subscriptionId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SubscriptionDirectiveEntity(
    val subscriptionId: Long,
    val key: String,
    val value: String,
    val receivedAt: Long,
) {
    override fun toString(): String =
        "SubscriptionDirectiveEntity(subscriptionId=$subscriptionId, key=$key, " +
            "value=<redacted>, receivedAt=$receivedAt)"
}

/**
 * What the user pinned.
 *
 * Spec D3: the provider wins by default, but a pinned value survives every
 * subsequent update. Effective value = override, else directive, else app
 * default — two rows and one read, which is what makes "the provider wants 6 h,
 * you pinned 24 h" renderable rather than merely knowable.
 *
 * [key] and [value] carry the same [SubscriptionDirectiveEntity] KDoc applies
 * to: [key] is registry vocabulary, [value] is user-pinned copy of a value
 * shaped by the same registry (a pinned `support-url` is still a URL), so it
 * gets the same redaction.
 */
@Entity(
    tableName = "subscription_overrides",
    primaryKeys = ["subscriptionId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SubscriptionOverrideEntity(
    val subscriptionId: Long,
    val key: String,
    val value: String,
    val pinnedAt: Long,
) {
    override fun toString(): String =
        "SubscriptionOverrideEntity(subscriptionId=$subscriptionId, key=$key, " +
            "value=<redacted>, pinnedAt=$pinnedAt)"
}
