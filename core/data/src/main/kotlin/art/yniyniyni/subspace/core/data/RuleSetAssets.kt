// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
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
@Suppress("TooManyFunctions") // Symlink-safe deletion helpers stay at this filesystem boundary.
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
    ): File {
        requireValidGeneration(generation)
        return File(setDir(setId), generation.toString())
    }

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
            if (Files.exists(directory.toPath(), NOFOLLOW_LINKS) && !deleteTreeNoFollow(directory)) {
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
        requireValidGeneration(keep)
        withContext(Dispatchers.IO) {
            try {
                setDir(setId).listFiles()?.forEach { child ->
                    if (child.name != keep.toString()) deleteTreeNoFollow(child)
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
            deleteTreeNoFollow(setDir(setId))
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
                    deleteEntryNoFollow(target)
                    return@withContext false
                }
                target.parentFile?.mkdirs()
                Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING)
                true
            } catch (_: IOException) {
                deleteEntryNoFollow(target)
                false
            } catch (_: SecurityException) {
                deleteEntryNoFollow(target)
                false
            }
        }

    /** Validates the set ID before deriving the only tree this class may delete. */
    private fun setDir(setId: Long): File {
        require(setId > 0) { "Rule set ID must be positive" }
        return File(setsRoot(), setId.toString())
    }

    /** Validates a generation before it becomes a directory name or a sweep keep value. */
    private fun requireValidGeneration(generation: Long) {
        require(generation > 0) { "Rule set generation must be positive" }
    }

    /**
     * Deletes [target] without following directory links, returning whether it was removed.
     *
     * The normalized containment and ancestor-link checks run before walking.
     * They keep a path that escaped through a symlinked set directory from ever
     * becoming the walk root; a symlink at [target] itself is a leaf and is
     * deleted as a link by the no-follow visitor.
     */
    @Suppress("SwallowedException") // Callers choose between strict and best-effort deletion.
    private fun deleteTreeNoFollow(target: File): Boolean {
        val rootPath = setsRoot().toPath().toAbsolutePath().normalize()
        val targetPath = target.toPath().toAbsolutePath().normalize()
        return if (!isSafeTreeTarget(rootPath, targetPath)) {
            false
        } else {
            try {
                if (Files.exists(targetPath, NOFOLLOW_LINKS)) {
                    Files.walkFileTree(targetPath, NoFollowDeletionVisitor)
                }
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    /** Deletes only [target]'s entry, so a symbolic link's destination remains untouched. */
    @Suppress("SwallowedException") // Copy failure intentionally degrades to a download.
    private fun deleteEntryNoFollow(target: File) {
        try {
            Files.deleteIfExists(target.toPath())
        } catch (_: IOException) {
            // The caller returns false and the failed download path handles the retry.
        } catch (_: SecurityException) {
            // The caller returns false and the failed download path handles the retry.
        }
    }

    /** Ensures [target] is beneath [root] and reaches it without a linked ancestor. */
    private fun isSafeTreeTarget(
        root: Path,
        target: Path,
    ): Boolean {
        if (target == root || !target.startsWith(root) || Files.isSymbolicLink(root)) return false
        var ancestor = root
        var safe = true
        root.relativize(target).forEach { segment ->
            ancestor = ancestor.resolve(segment)
            if (ancestor != target && Files.isSymbolicLink(ancestor)) safe = false
        }
        return safe
    }

    /** A [Files.walkFileTree] visitor that deletes entries without following symbolic links. */
    private data object NoFollowDeletionVisitor : SimpleFileVisitor<Path>() {
        override fun visitFile(
            file: Path,
            attributes: BasicFileAttributes,
        ): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(
            directory: Path,
            exception: IOException?,
        ): FileVisitResult {
            exception?.let { throw it }
            Files.delete(directory)
            return FileVisitResult.CONTINUE
        }
    }
}
