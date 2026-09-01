// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val RETENTION_DIR = ".routing-generation-retention"
private const val LOCK_SUFFIX = ".lock"
private const val DELETE_SUFFIX = ".delete"

/** Result of using the resolved routing asset directory through its ownership scope. */
public sealed interface ResolvedAssetUse<out T> {
    /** The callback ran while the exact directory remained retained. */
    public data class Used<T>(public val value: T) : ResolvedAssetUse<T>

    /** The generation was already pending deletion and must be resolved again. */
    public data object GenerationUnavailable : ResolvedAssetUse<Nothing>
}

/**
 * Scoped access to one rule set's asset directory.
 *
 * Implementations own every lock and channel. Callers receive only the directory
 * and cannot forget to release a raw closeable.
 */
public interface RuleSetAssetScope {
    public suspend fun <T> withResolvedAssetDir(
        setId: Long,
        generation: Long,
        hasOwnSources: Boolean,
        block: suspend (File) -> T,
    ): ResolvedAssetUse<T>
}

/** One acquired generation lock whose backend owns every OS resource. */
internal fun interface GenerationFileLock {
    fun release()
}

/** Blocking lock acquisition, injectable so the cross-process protocol can be interleaved deterministically. */
internal fun interface GenerationFileLockBackend {
    fun acquire(lockPath: Path): GenerationFileLock?
}

private object NioGenerationFileLockBackend : GenerationFileLockBackend {
    @Suppress("ReturnCount") // Every failed filesystem stage closes its own resources and fails closed.
    override fun acquire(lockPath: Path): GenerationFileLock? {
        // Never unlink a lock-path symlink. Replacing it would create a new
        // inode that can be locked independently of a process using the old one.
        if (Files.isSymbolicLink(lockPath)) return null
        val channel =
            try {
                FileChannel.open(lockPath, setOf<OpenOption>(CREATE, WRITE, NOFOLLOW_LINKS))
            } catch (_: IOException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
        val lock =
            try {
                channel.lock()
            } catch (_: OverlappingFileLockException) {
                channel.closeQuietly()
                return null
            } catch (_: IOException) {
                channel.closeQuietly()
                return null
            }
        return GenerationFileLock {
            lock.releaseQuietly()
            channel.closeQuietly()
        }
    }
}

/**
 * One process's ownership registry. Separate Android processes own separate
 * instances while coordinating through [GenerationFileLockBackend].
 *
 * Lock inodes live outside `sets/<id>/<generation>`, so deleting a generation
 * never unlinks an inode another process is still using for coordination. The
 * lock files deliberately remain after release: unlinking one while another
 * process has opened it would permit a second lock on a replacement inode.
 */
@Suppress("TooManyFunctions") // Protocol stages stay together so their marker/lock ordering is reviewable.
internal class GenerationRetentionRegistry(
    private val lockBackend: GenerationFileLockBackend = NioGenerationFileLockBackend,
) {
    private val guards = ConcurrentHashMap<Path, Any>()
    private val held = ConcurrentHashMap<Path, HeldLock>()

    @Suppress("ReturnCount") // Each early return closes any channel acquired for one ownership outcome.
    fun acquire(
        root: File,
        setId: Long,
        generation: Long,
        generationDir: File,
        deleteGeneration: () -> Boolean,
    ): GenerationLease? {
        val paths = retentionPaths(root, setId, generation)
        val guard = guards.computeIfAbsent(paths.lock) { Any() }
        synchronized(guard) {
            held[paths.lock]?.let { existing ->
                existing.references += 1
                return GenerationLease(this, paths.lock, generationDir, existing, deleteGeneration)
            }

            val lock = acquireFileLock(paths) ?: return null

            if (markerExists(paths.marker)) {
                reapMarkedGeneration(paths.marker, generationDir, deleteGeneration)
                lock.release()
                return null
            }

            val entry = HeldLock(lock)
            held[paths.lock] = entry
            return GenerationLease(this, paths.lock, generationDir, entry, deleteGeneration)
        }
    }

    /**
     * Records delete intent before waiting for ownership, then consumes it
     * under the same lock a lease holder releases.
     *
     * Marker-first ordering covers every crash point: a holder can consume the
     * marker, this caller can acquire after the holder and consume it, or a
     * later operation can recover a marker left by process death.
     */
    @Suppress("ReturnCount") // Unsafe paths, local leases, lock failures, and consumed intent differ.
    fun requestDelete(
        root: File,
        setId: Long,
        generation: Long,
        generationDir: File,
        deleteGeneration: () -> Boolean,
    ): Boolean {
        val paths = retentionPaths(root, setId, generation)
        if (!createMarker(paths)) return false
        val guard = guards.computeIfAbsent(paths.lock) { Any() }
        synchronized(guard) {
            if (held.containsKey(paths.lock)) return false

            // Blocking is deliberate: if a holder passed its final marker read,
            // this caller must acquire after unlock and perform the recheck.
            val lock = acquireFileLock(paths) ?: return false

            return try {
                if (markerExists(paths.marker)) {
                    reapMarkedGeneration(paths.marker, generationDir, deleteGeneration)
                } else {
                    !entryExists(generationDir)
                }
            } finally {
                lock.release()
            }
        }
    }

    private fun release(lease: GenerationLease) {
        val guard = guards.computeIfAbsent(lease.lockPath) { Any() }
        synchronized(guard) {
            val entry = held[lease.lockPath]
            if (entry !== lease.entry) return
            entry.references -= 1
            if (entry.references > 0) return

            val marker =
                lease.lockPath.resolveSibling(
                    lease.lockPath.fileName.toString().removeSuffix(LOCK_SUFFIX) + DELETE_SUFFIX,
                )
            if (markerExists(marker)) {
                reapMarkedGeneration(marker, lease.generationDir, lease.deleteGeneration)
            }
            held.remove(lease.lockPath, entry)
            entry.lock.release()
        }
    }

    private fun retentionPaths(
        root: File,
        setId: Long,
        generation: Long,
    ): RetentionPaths {
        require(setId > 0) { "Rule set ID must be positive" }
        require(generation > 0) { "Rule set generation must be positive" }
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val setPath = rootPath.resolve(RETENTION_DIR).resolve(setId.toString()).normalize()
        check(setPath.startsWith(rootPath) && setPath != rootPath) { "Retention path escaped geo root" }
        return RetentionPaths(
            root = rootPath,
            setDirectory = setPath,
            lock = setPath.resolve("$generation$LOCK_SUFFIX"),
            marker = setPath.resolve("$generation$DELETE_SUFFIX"),
        )
    }

    /** Creates fixed/numeric retention directories without following linked entries. */
    @Suppress("NestedBlockDepth") // Each fixed path component is verified before the next may be created.
    private fun ensureRetentionDirectory(paths: RetentionPaths): Boolean =
        try {
            if (Files.isSymbolicLink(paths.root)) return false
            var current = paths.root
            listOf(RETENTION_DIR, paths.setDirectory.fileName.toString()).forEach { name ->
                current = current.resolve(name)
                if (Files.exists(current, NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) return false
                } else {
                    try {
                        Files.createDirectory(current)
                    } catch (_: FileAlreadyExistsException) {
                        if (Files.isSymbolicLink(current) || !Files.isDirectory(current, NOFOLLOW_LINKS)) return false
                    }
                }
            }
            current == paths.setDirectory
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun acquireFileLock(paths: RetentionPaths): GenerationFileLock? {
        if (!ensureRetentionDirectory(paths)) return null
        return lockBackend.acquire(paths.lock)
    }

    private fun createMarker(paths: RetentionPaths): Boolean {
        if (!ensureRetentionDirectory(paths)) return false
        return try {
            if (!Files.exists(paths.marker, NOFOLLOW_LINKS)) Files.createFile(paths.marker)
            true
        } catch (_: FileAlreadyExistsException) {
            // Any no-follow entry at the exact marker path already means delete requested.
            true
        } catch (_: IOException) {
            // Best effort. A later sweep can retry without risking a live generation.
            markerExists(paths.marker)
        } catch (_: SecurityException) {
            // Best effort, as above.
            false
        }
    }

    private fun markerExists(marker: Path): Boolean =
        try {
            Files.exists(marker, NOFOLLOW_LINKS)
        } catch (_: SecurityException) {
            false
        }

    private fun reapMarkedGeneration(
        marker: Path,
        generationDir: File,
        deleteGeneration: () -> Boolean,
    ): Boolean {
        if (entryExists(generationDir) && !deleteGeneration()) return false
        deleteMarker(marker)
        return !entryExists(generationDir) && !markerExists(marker)
    }

    private fun entryExists(entry: File): Boolean = Files.exists(entry.toPath(), NOFOLLOW_LINKS)

    /** `deleteIfExists` removes a marker symlink itself, never its destination. */
    private fun deleteMarker(marker: Path) {
        try {
            Files.deleteIfExists(marker)
        } catch (_: IOException) {
            // Leaving the marker requests another safe reap later.
        } catch (_: SecurityException) {
            // Leaving the marker requests another safe reap later.
        }
    }

    internal class GenerationLease internal constructor(
        private val owner: GenerationRetentionRegistry,
        internal val lockPath: Path,
        internal val generationDir: File,
        internal val entry: HeldLock,
        internal val deleteGeneration: () -> Boolean,
    ) {
        private val released = AtomicBoolean(false)

        /** Idempotent so cancellation and explicit teardown may converge safely. */
        fun release() {
            if (released.compareAndSet(false, true)) owner.release(this)
        }
    }

    internal class HeldLock(
        val lock: GenerationFileLock,
        var references: Int = 1,
    )

    private data class RetentionPaths(
        val root: Path,
        val setDirectory: Path,
        val lock: Path,
        val marker: Path,
    )
}

/** Production registry, process-local by construction. */
internal val GenerationRetention: GenerationRetentionRegistry = GenerationRetentionRegistry()

private fun FileLock.releaseQuietly() {
    try {
        release()
    } catch (_: IOException) {
        // The channel close below is the final ownership release.
    }
}

private fun FileChannel.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // No recovery remains; OS process teardown also releases channel locks.
    }
}
