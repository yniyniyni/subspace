// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val SETS_DIR = "sets"
private const val VALIDATED_DIGEST_PREFIX = ".subspace-validated-"
private const val VALIDATED_DIGEST_SUFFIX = ".sha256"
private const val SHA_256_HEX_LENGTH = 64
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Copies regular files in bounded chunks with a cancellation checkpoint between operations.
 *
 * Android/JVM cannot forcibly interrupt one filesystem `read` or `write` already executing in
 * the kernel. App-private regular files make those individual operations bounded in practice;
 * this avoids one uninterruptible whole-file `Files.copy` and observes cancellation before every
 * next 64 KiB operation. [afterChunk] exposes the real boundary for deterministic timeout tests.
 */
internal class CooperativeRuleSetFileCopier(
    private val afterChunk: suspend (copiedBytes: Long) -> Unit = {},
) {
    @Suppress("NestedBlockDepth") // Input, output, and chunk loop must share deterministic close scopes.
    suspend fun copy(
        source: File,
        target: File,
    ) {
        FileInputStream(source).buffered().use { input ->
            FileOutputStream(target).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    currentCoroutineContext().ensureActive()
                    output.write(buffer, 0, count)
                    copied += count
                    currentCoroutineContext().ensureActive()
                    afterChunk(copied)
                }
            }
        }
    }
}

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
internal constructor(
    @GeoAssetRoot private val root: File,
    private val copier: CooperativeRuleSetFileCopier,
) {
    @Inject
    internal constructor(
        @GeoAssetRoot root: File,
    ) : this(root, CooperativeRuleSetFileCopier())

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
            val rootPath = setsRoot().toPath().toAbsolutePath().normalize()
            val directoryPath = directory.toPath().toAbsolutePath().normalize()
            if (!isSafeTreeTarget(rootPath, directoryPath)) {
                throw IOException("Refusing to create geo generation through a linked ancestor")
            }
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
            var complete = false
            try {
                if (!source.isFile) {
                    return@withContext false
                }
                target.parentFile?.mkdirs()
                deleteEntryNoFollow(target)
                copier.copy(source, target)
                complete = true
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } finally {
                if (!complete) deleteEntryNoFollow(target)
            }
        }

    /**
     * Records the digest of one validated generation file before that generation is published.
     *
     * Both the `.dat` and this internal metadata file are derived from [generationDir], so Room
     * cannot expose the proof independently of the generation it describes. A failed digest write
     * leaves publication to the caller, which must reject the staged generation.
     */
    internal suspend fun recordValidatedFile(
        setId: Long,
        generation: Long,
        fileName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            requireValidAssetFileName(fileName)
            val directory = generationDir(setId, generation)
            val data = File(directory, fileName)
            val metadata = validatedDigestFile(directory, fileName)
            val temporary = File(directory, "${metadata.name}.pending")
            try {
                if (!data.isFile) return@withContext false
                val digest = data.sha256()
                temporary.writeText(digest)
                Files.move(temporary.toPath(), metadata.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                metadata.isFile
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } finally {
                deleteEntryNoFollow(temporary)
            }
        }

    /** Returns a live generation file only while its bytes match staged validation metadata. */
    internal suspend fun verifiedGenerationFile(
        setId: Long,
        generation: Long,
        fileName: String,
    ): File? =
        withContext(Dispatchers.IO) {
            requireValidAssetFileName(fileName)
            try {
                val directory = generationDir(setId, generation)
                val data = File(directory, fileName)
                val expected = readValidatedDigest(directory, fileName) ?: return@withContext null
                data.takeIf { it.isFile && it.sha256() == expected }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
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

    private fun requireValidAssetFileName(fileName: String) {
        require(fileName == "geoip.dat" || fileName == "geosite.dat") {
            "Unsupported routing geo file"
        }
    }

    private fun validatedDigestFile(
        generation: File,
        fileName: String,
    ): File = File(generation, "$VALIDATED_DIGEST_PREFIX$fileName$VALIDATED_DIGEST_SUFFIX")

    private fun readValidatedDigest(
        generation: File,
        fileName: String,
    ): String? =
        try {
            validatedDigestFile(generation, fileName)
                .readText()
                .takeIf { it.length == SHA_256_HEX_LENGTH && it.all { char -> char.isLowerHexDigit() } }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    @Suppress("MagicNumber") // 64 KiB bounds cancellation latency between regular-file reads.
    private suspend fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).buffered().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

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
