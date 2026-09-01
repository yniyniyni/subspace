// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
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
    private val afterCopy: suspend (source: File, target: File) -> Unit = { _, _ -> },
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
        afterCopy(source, target)
    }
}

/** Forces one completed generation file to stable storage before publication may continue. */
internal fun interface StableFileForcer {
    fun force(file: File)
}

private val fileChannelForcer =
    StableFileForcer { file ->
        FileChannel.open(file.toPath(), WRITE).use { channel -> channel.force(true) }
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
    private val fileForcer: StableFileForcer = fileChannelForcer,
) : RuleSetAssetScope {
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
     * Runs [block] while the exact own-source generation is retained.
     *
     * Acquisition and release perform blocking channel work on IO. The callback
     * may suspend on any dispatcher: Java file locks belong to their channel,
     * not to the thread that acquired them. Release runs in `NonCancellable` and
     * no lock or channel escapes this scope.
     */
    @Suppress("ReturnCount") // Shared, unavailable, and retained are the complete ownership outcomes.
    override suspend fun <T> withResolvedAssetDir(
        setId: Long,
        generation: Long,
        hasOwnSources: Boolean,
        block: suspend (File) -> T,
    ): ResolvedAssetUse<T> {
        if (!hasOwnSources) return ResolvedAssetUse.Used(block(sharedRoot()))
        val directory = generationDir(setId, generation)
        val lease =
            withContext(NonCancellable + Dispatchers.IO) {
                GenerationRetention.acquire(root, setId, generation, directory) {
                    deleteGenerationAndEmptySet(directory)
                }
            } ?: return ResolvedAssetUse.GenerationUnavailable
        return try {
            currentCoroutineContext().ensureActive()
            ResolvedAssetUse.Used(block(directory))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { lease.release() }
        }
    }

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
     * The geo filenames actually present in [directory].
     *
     * The same question `RoutingResolver` asks at the service boundary, so the
     * activation gate on screen and the resolution the tunnel performs cannot
     * disagree. Asking the shared root for a row that reads its own generation
     * is what let the list enable a profile whose generation was absent because
     * a same-named shared file happened to exist.
     */
    public suspend fun installedFileNames(directory: File): Set<String> =
        withContext(Dispatchers.IO) {
            directory.listFiles().orEmpty().filter(File::isFile).map(File::getName).toSet()
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
                    if (child.name != keep.toString()) deleteGenerationOrEntry(setId, child)
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
            val directory = setDir(setId)
            if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) {
                deleteTreeNoFollow(directory)
            } else {
                directory.listFiles()?.forEach { child -> deleteGenerationOrEntry(setId, child) }
                deleteEmptyDirectoryNoFollow(directory)
            }
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
        expectedDigest: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            requireValidAssetFileName(fileName)
            require(expectedDigest == null || expectedDigest.isValidatedDigest()) {
                "Expected digest must be lowercase SHA-256"
            }
            val directory = generationDir(setId, generation)
            val data = File(directory, fileName)
            val metadata = validatedDigestFile(directory, fileName)
            val temporary = File(directory, "${metadata.name}.pending")
            try {
                if (!data.isFile) return@withContext false
                val digest = data.sha256()
                if (expectedDigest != null && digest != expectedDigest) return@withContext false
                fileForcer.force(data)
                temporary.writeText(digest)
                fileForcer.force(temporary)
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

    /**
     * Copies one source generation file and records it only if the target retains the source digest.
     *
     * The source's Ready state is not accepted as proof: [validatedGenerationDigest] re-hashes its
     * live bytes against its forced metadata first, then [recordValidatedFile] independently hashes
     * the copied target before either target file may become publishable.
     */
    internal suspend fun copyValidatedFile(
        sourceSetId: Long,
        sourceGeneration: Long,
        targetSetId: Long,
        targetGeneration: Long,
        fileName: String,
    ): Boolean {
        val expectedDigest = validatedGenerationDigest(sourceSetId, sourceGeneration, fileName) ?: return false
        val source = File(generationDir(sourceSetId, sourceGeneration), fileName)
        val target = File(generationDir(targetSetId, targetGeneration), fileName)
        return copyLocally(source, target) &&
            recordValidatedFile(targetSetId, targetGeneration, fileName, expectedDigest)
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
                validatedGenerationDigest(setId, generation, fileName)?.let {
                    File(generationDir(setId, generation), fileName)
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    private suspend fun validatedGenerationDigest(
        setId: Long,
        generation: Long,
        fileName: String,
    ): String? =
        withContext(Dispatchers.IO) {
            requireValidAssetFileName(fileName)
            val directory = generationDir(setId, generation)
            val data = File(directory, fileName)
            val expected = readValidatedDigest(directory, fileName) ?: return@withContext null
            expected.takeIf { data.isFile && data.sha256() == expected }
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

    /** Numeric children are generation-owned; every other stale entry is ordinary filesystem debris. */
    private fun deleteGenerationOrEntry(
        setId: Long,
        child: File,
    ) {
        val generation = child.name.toLongOrNull()?.takeIf { it > 0 }
        if (generation == null) {
            deleteTreeNoFollow(child)
        } else {
            GenerationRetention.requestDelete(root, setId, generation, child) {
                deleteGenerationAndEmptySet(child)
            }
        }
    }

    private fun deleteGenerationAndEmptySet(directory: File): Boolean {
        val deleted = deleteTreeNoFollow(directory)
        if (deleted) directory.parentFile?.let(::deleteEmptyDirectoryNoFollow)
        return deleted
    }

    /** Removes only an empty real directory; a leased generation keeps its parent non-empty. */
    @Suppress("SwallowedException") // Best-effort parent cleanup never broadens the deletion target.
    private fun deleteEmptyDirectoryNoFollow(directory: File) {
        try {
            if (!Files.isSymbolicLink(directory.toPath())) Files.deleteIfExists(directory.toPath())
        } catch (_: IOException) {
            // Non-empty means another generation is live or retained.
        } catch (_: SecurityException) {
            // A later sweep can retry this empty-parent cleanup.
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
                .takeIf { digest -> digest.isValidatedDigest() }
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

    private fun String.isValidatedDigest(): Boolean =
        length == SHA_256_HEX_LENGTH && all { char -> char.isLowerHexDigit() }

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
