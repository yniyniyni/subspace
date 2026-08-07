// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

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
    val lastFetchStatus: String?,
    val lastFetchDetail: String?,
    val createdAt: Long,
)

/**
 * What the provider last sent, **after** validation.
 *
 * §A.1: *"validate every value against a schema before it reaches storage"*.
 * Nothing unvalidated reaches this table — `DirectiveValidator` is the only
 * writer's source, and its accepted map is by construction registry keys with
 * canonicalised values.
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
)

/**
 * What the user pinned.
 *
 * Spec D3: the provider wins by default, but a pinned value survives every
 * subsequent update. Effective value = override, else directive, else app
 * default — two rows and one read, which is what makes "the provider wants 6 h,
 * you pinned 24 h" renderable rather than merely knowable.
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
)
