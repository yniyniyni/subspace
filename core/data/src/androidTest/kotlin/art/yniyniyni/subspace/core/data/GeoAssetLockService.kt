// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.data

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import art.yniyniyni.subspace.core.data.di.subspaceDatabase
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.model.GeoValidation
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/**
 * Performs an install in a separate process for [GeoAssetRepositoryTest].
 *
 * The test holds the target lock before binding this service. Its `started`
 * marker is deliberately written from the download callback, so its absence
 * proves this process was blocked by the OS lock rather than merely slow to
 * start.
 */
class GeoAssetLockService : Service() {
    private var databaseName: String? = null

    override fun onBind(intent: Intent): IBinder {
        databaseName = requireNotNull(intent.getStringExtra(EXTRA_DATABASE_NAME))
        File(requireNotNull(intent.getStringExtra(EXTRA_READY_FILE))).writeText(Process.myPid().toString())
        Thread({ install(intent) }, "geo-asset-lock-writer").start()
        return Binder()
    }

    private fun install(intent: Intent) =
        runBlocking {
            val root = File(requireNotNull(intent.getStringExtra(EXTRA_ROOT)))
            val started = File(requireNotNull(intent.getStringExtra(EXTRA_STARTED_FILE)))
            val result = File(requireNotNull(intent.getStringExtra(EXTRA_RESULT_FILE)))
            val closed = File(requireNotNull(intent.getStringExtra(EXTRA_CLOSED_FILE)))
            val database = subspaceDatabase(applicationContext, requireNotNull(databaseName))
            val installResult =
                try {
                    val repository =
                        GeoAssetRepository(
                            dao = database.geoAssetDao(),
                            validator = sidecarValidator,
                            root = root,
                            download = { _, target ->
                                started.writeText(Process.myPid().toString())
                                target.writeText("from second process")
                                target.length() to "second-process-digest"
                            },
                            clock = { 1L },
                        )
                    repository.install(
                        GeoInstallRequest(
                            fileName = FILE_NAME,
                            sourceUrl = "https://example.invalid/geo.dat",
                            geoType = GeoDataKind.DOMAIN,
                        ),
                    )
                } finally {
                    database.close()
                }
            // This is the cross-process completion barrier: every Room and
            // repository handle is closed before the other process may clean root.
            closed.writeText(Process.myPid().toString())
            val pendingResult = File(result.parentFile, "${result.name}.pending")
            pendingResult.writeText(installResult.name)
            Files.move(pendingResult.toPath(), result.toPath(), ATOMIC_MOVE)
        }

    companion object {
        const val EXTRA_DATABASE_NAME = "art.yniyniyni.subspace.core.data.geo.lock.database"
        const val EXTRA_ROOT = "art.yniyniyni.subspace.core.data.geo.lock.root"
        const val EXTRA_READY_FILE = "art.yniyniyni.subspace.core.data.geo.lock.ready"
        const val EXTRA_STARTED_FILE = "art.yniyniyni.subspace.core.data.geo.lock.started"
        const val EXTRA_RESULT_FILE = "art.yniyniyni.subspace.core.data.geo.lock.result"
        const val EXTRA_CLOSED_FILE = "art.yniyniyni.subspace.core.data.geo.lock.closed"
        const val FILE_NAME = "geosite.dat"

        private val sidecarValidator =
            object : GeoDataValidator {
                override suspend fun validate(
                    datDir: File,
                    name: String,
                    kind: GeoDataKind,
                ): GeoValidation {
                    File(datDir, "$name.json").writeText("{\"codes\":[]}")
                    return GeoValidation.Valid
                }
            }
    }
}
