// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import android.content.Context
import androidx.room.Room
import art.yniyniyni.subspace.core.data.db.GeoAssetDao
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.GeoDataValidator
import java.io.File

/**
 * A real [GeoAssetRepository] over an in-memory database and a temporary
 * directory, with the download and the clock supplied by the test.
 *
 * Same rationale as `InMemorySubscriptionStack` (§11): the repository's
 * constructor is `internal`, and `testFixtures` is the sanctioned way to hand a
 * real one to a test in another module.
 */
public class InMemoryGeoStack(
    context: Context,
    private val validator: GeoDataValidator,
    private val download: suspend (url: String, target: File) -> Pair<Long, String>,
    public val now: Long = 1_754_000_000_000L,
) : AutoCloseable {
    private val database =
        Room
            .inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private val root: File =
        File(context.cacheDir, "geo-stack-${System.nanoTime()}").apply {
            deleteRecursively()
            mkdirs()
        }

    public val repository: GeoAssetRepository =
        GeoAssetRepository(
            dao = database.geoAssetDao(),
            validator = validator,
            root = root,
            download = download,
            clock = { now },
        )

    /** Builds a second repository over this test's directory and database. */
    internal fun repositoryWith(
        dao: GeoAssetDao,
        copyForRollback: ((source: File, target: File) -> Unit)? = null,
    ): GeoAssetRepository =
        if (copyForRollback == null) {
            GeoAssetRepository(
                dao = dao,
                validator = validator,
                root = root,
                download = download,
                clock = { now },
            )
        } else {
            GeoAssetRepository(
                dao = dao,
                validator = validator,
                root = root,
                download = download,
                clock = { now },
                copyForRollback = copyForRollback,
            )
        }

    /** The real DAO, exposed only for failure-injection tests in :core:data. */
    internal fun geoAssetDao(): GeoAssetDao = database.geoAssetDao()

    override fun close() {
        database.close()
        root.deleteRecursively()
    }
}
