// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton

private const val SETS_DIR = "sets"

/**
 * Where one rule set's own geo databases live, generation by generation.
 *
 * Files for a new generation are complete and validated before Task 8 publishes
 * its number with [RoutingRepository.commitGeneration]. The old generation then
 * remains readable until [sweepExcept] runs, so a failed or interrupted update
 * cannot leave routing rules pointing at partial files (spec §7.4).
 */
@Singleton
public class RuleSetAssets
@Inject
internal constructor(
    @GeoAssetRoot private val root: File,
) {
    /** The flat catalogue directory used by hand-made sets without their own sources. */
    public fun sharedRoot(): File = root

    /** The parent directory for all per-set generations: `geo/sets`. */
    public fun setsRoot(): File = File(root, SETS_DIR)

    /** `geo/sets/<setId>/<generation>`, without creating it. */
    public fun generationDir(
        setId: Long,
        generation: Long,
    ): File = File(setDir(setId), generation.toString())

    /**
     * Returns the directory Xray must read for this set.
     *
     * Hand-made sets retain M5's flat shared catalogue root. A set that names
     * its own sources resolves exactly to its published generation directory.
     */
    public fun resolveAssetDir(
        setId: Long,
        generation: Long,
        hasOwnSources: Boolean,
    ): File = if (hasOwnSources) generationDir(setId, generation) else sharedRoot()

    /**
     * Recreates an empty directory for [generation].
     *
     * Reusing a generation after an interrupted attempt must not retain a
     * partially downloaded database, so stale content is removed before the
     * caller begins writing new files.
     */
    public suspend fun prepareGeneration(
        setId: Long,
        generation: Long,
    ): File =
        withContext(Dispatchers.IO) {
            val directory = generationDir(setId, generation)
            if (directory.exists() && !directory.deleteRecursively()) {
                throw IOException("Could not clear geo generation directory")
            }
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Could not create geo generation directory")
            }
            directory
        }

    /**
     * Best-effort removal of every generation for [setId] other than [keep].
     *
     * A failed sweep costs disk space only. In particular, [keep] is never
     * passed to deletion, because it is the generation Room has published.
     */
    @Suppress("SwallowedException") // Sweeping is intentionally best-effort.
    public suspend fun sweepExcept(
        setId: Long,
        keep: Long,
    ) {
        withContext(Dispatchers.IO) {
            try {
                setDir(setId).listFiles()?.forEach { child ->
                    if (child.name != keep.toString()) child.deleteRecursively()
                }
            } catch (_: SecurityException) {
                // A later sweep can reclaim any files this one could not access.
            }
        }
    }

    /**
     * Removes exactly [setId]'s generation tree.
     *
     * The positive-ID guard makes a broad `geo/sets` or shared-root deletion
     * unrepresentable from this API.
     */
    public suspend fun removeSet(setId: Long) {
        withContext(Dispatchers.IO) {
            setDir(setId).deleteRecursively()
        }
    }

    /**
     * Copies a locally available database into a generation without hard-linking it.
     *
     * Returns `false` for any local-copy failure so the caller can download the
     * file instead. A previous or partial target is removed in that case.
     */
    @Suppress("SwallowedException") // The boolean is the caller-facing failure report.
    public suspend fun copyLocally(
        source: File,
        target: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!source.isFile) {
                    target.delete()
                    return@withContext false
                }
                target.parentFile?.mkdirs()
                Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING)
                true
            } catch (_: IOException) {
                target.delete()
                false
            } catch (_: SecurityException) {
                target.delete()
                false
            }
        }

    /** Validates the set ID before deriving the only tree this class may delete. */
    private fun setDir(setId: Long): File {
        require(setId > 0) { "Rule set ID must be positive" }
        return File(setsRoot(), setId.toString())
    }
}
