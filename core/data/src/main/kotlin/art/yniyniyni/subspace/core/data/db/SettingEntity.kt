// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single setting.
 *
 * ARCHITECTURE.md §3: "Do not add `androidx.datastore`. If you think you need
 * it, you want a Room table." Preferences DataStore is not multi-process safe
 * and this app runs two processes; one storage engine means one invalidation
 * mechanism and one place to reason about concurrency.
 */
@Entity(tableName = "settings")
internal data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
