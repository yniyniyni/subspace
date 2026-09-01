// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One geo database that has been installed, or attempted.
 *
 * Keyed by the install filename because that is what an `ext:` reference names
 * and what the routing activation gate looks for. Replacing a source for the
 * same filename updates this row rather than creating a second asset.
 */
@Entity(tableName = "geo_assets")
internal data class GeoAssetEntity(
    @PrimaryKey val fileName: String,
    val sourceUrl: String,
    val geoType: String,
    val sha256: String?,
    val sizeBytes: Long?,
    val installedAt: Long?,
    val lastAttemptedAt: Long?,
    val lastFailure: String?,
)
