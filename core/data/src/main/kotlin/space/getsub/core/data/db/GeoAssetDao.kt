// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Data access for [GeoAssetEntity]. */
@Dao
internal interface GeoAssetDao {
    @Query("SELECT * FROM geo_assets ORDER BY fileName ASC")
    fun observeAll(): Flow<List<GeoAssetEntity>>

    @Query("SELECT * FROM geo_assets WHERE fileName = :fileName")
    suspend fun byFileName(fileName: String): GeoAssetEntity?

    @Query("SELECT fileName FROM geo_assets WHERE installedAt IS NOT NULL")
    suspend fun installedFileNames(): List<String>

    @Upsert
    suspend fun upsert(entity: GeoAssetEntity)

    @Query("DELETE FROM geo_assets WHERE fileName = :fileName")
    suspend fun deleteByFileName(fileName: String)
}
