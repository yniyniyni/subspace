// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One stored server.
 *
 * ARCHITECTURE.md §6: storage follows provenance. [kind] is `TYPED` for share
 * links, base64 lists and Clash YAML — bounded formats, stored as a serialized
 * [outbound]. It is `RAW_JSON` for a hand-written config, which additionally
 * keeps [rawJson] byte-for-byte because extraction is lossy and the loss is
 * permanent once the pasted text is gone.
 *
 * [protocol], [address], [port] and [transport] are shadow columns derived on
 * write. The Servers screen filters and searches on them, and doing that in SQL
 * beats deserializing every row. They are never a second source of truth.
 *
 * [identityHash] covers the **whole** outbound, which is what makes the unique
 * index safe. M2's `Profile.id` hashed protocol/address/port/credential only,
 * so two variants of one server differing solely in SNI or flow collapsed into
 * a single id — fine for re-import dedup, silent data loss at an upsert.
 *
 * [lastConnectedAt] and [lastError] are the only columns `:bg` writes.
 *
 * [subscriptionKey] is the provider's own identity for a server within its
 * subscription, and it is what refresh keys on — **not** [identityHash]. The M3
 * spec's handoff note is the reason: because [identityHash] covers the whole
 * outbound, a provider changing a server's SNI produces a *new* row rather than
 * an update, orphaning the old one along with its connection history. NULL for
 * every hand-imported profile; SQLite treats NULLs as distinct in a unique
 * index, so any number of manual rows coexist and only subscription-backed rows
 * are constrained.
 */
@Entity(
    tableName = "profiles",
    foreignKeys = [
        ForeignKey(
            entity = ProfileGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "identityHash"], unique = true),
        Index(value = ["groupId", "subscriptionKey"], unique = true),
        Index(value = ["groupId", "position"]),
    ],
)
internal data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val kind: String,
    val identityHash: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val transport: String,
    val outbound: String,
    val rawJson: String?,
    val position: Int,
    val lastConnectedAt: Long?,
    val lastError: String?,
    val createdAt: Long,
    val subscriptionKey: String? = null,
)
