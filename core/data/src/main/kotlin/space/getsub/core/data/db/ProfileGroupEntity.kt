// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A folder of profiles.
 *
 * Table name is `profile_groups`, not `groups`: `GROUPS` is a SQLite keyword
 * (window functions, 3.28+) and an unquoted reference to it is a syntax error.
 *
 * [source] is the M4 seam. Subscription-backed groups become
 * `source = "SUBSCRIPTION"` in this same table, so adding subscriptions needs
 * no migration and the Servers screen already knows how to draw a group.
 */
@Entity(tableName = "profile_groups")
internal data class ProfileGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val source: String,
    val position: Int,
    val createdAt: Long,
)
