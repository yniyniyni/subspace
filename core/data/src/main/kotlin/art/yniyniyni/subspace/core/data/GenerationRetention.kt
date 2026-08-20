// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

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

/**
 * Process-local reference ownership backed by cross-process OS file locks.
 *
 * Lock inodes live outside `sets/<id>/<generation>`, so deleting a generation
 * never unlinks an inode another process is still using for coordination. The
 * lock files deliberately remain after release: unlinking one while another
 * process has opened it would permit a second lock on a replacement inode.
 */
internal object GenerationRetention {
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
                return GenerationLease(paths.lock, existing, deleteGeneration)
            }

            val channel = openLockChannel(paths) ?: return null
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

            if (markerExists(paths.marker)) {
                reapMarkedGeneration(paths.marker, generationDir, deleteGeneration)
                lock.releaseQuietly()
                channel.closeQuietly()
                return null
            }

            val entry = HeldLock(channel, lock)
            held[paths.lock] = entry
            return GenerationLease(paths.lock, entry, deleteGeneration)
        }
    }

    /** Deletes now when unleased, or records a safe delete-on-release request. */
    @Suppress("ReturnCount") // Leased, lock failure, delete success, and deferred delete are distinct outcomes.
    fun requestDelete(
        root: File,
        setId: Long,
        generation: Long,
        deleteGeneration: () -> Boolean,
    ): Boolean {
        val paths = retentionPaths(root, setId, generation)
        val guard = guards.computeIfAbsent(paths.lock) { Any() }
        synchronized(guard) {
            if (held.containsKey(paths.lock)) {
                createMarker(paths)
                return false
            }

            val channel = openLockChannel(paths)
            if (channel == null) {
                createMarker(paths)
                return false
            }
            val lock =
                try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                } catch (_: IOException) {
                    null
                }
            if (lock == null) {
                channel.closeQuietly()
                createMarker(paths)
                return false
            }

            return try {
                if (deleteGeneration()) {
                    deleteMarker(paths.marker)
                    true
                } else {
                    createMarker(paths)
                    false
                }
            } finally {
                lock.releaseQuietly()
                channel.closeQuietly()
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
                if (lease.deleteGeneration()) deleteMarker(marker)
            }
            held.remove(lease.lockPath, entry)
            entry.lock.releaseQuietly()
            entry.channel.closeQuietly()
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

    private fun openLockChannel(paths: RetentionPaths): FileChannel? {
        if (!ensureRetentionDirectory(paths)) return null
        return try {
            if (Files.isSymbolicLink(paths.lock)) Files.deleteIfExists(paths.lock)
            FileChannel.open(paths.lock, setOf<OpenOption>(CREATE, WRITE, NOFOLLOW_LINKS))
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun createMarker(paths: RetentionPaths) {
        if (!ensureRetentionDirectory(paths)) return
        try {
            if (!Files.exists(paths.marker, NOFOLLOW_LINKS)) Files.createFile(paths.marker)
        } catch (_: FileAlreadyExistsException) {
            // Any no-follow entry at the exact marker path already means delete requested.
        } catch (_: IOException) {
            // Best effort. A later sweep can retry without risking a live generation.
        } catch (_: SecurityException) {
            // Best effort, as above.
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
    ) {
        if (Files.exists(generationDir.toPath(), NOFOLLOW_LINKS) && !deleteGeneration()) return
        deleteMarker(marker)
    }

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
        internal val lockPath: Path,
        internal val entry: HeldLock,
        internal val deleteGeneration: () -> Boolean,
    ) {
        private val released = AtomicBoolean(false)

        /** Idempotent so cancellation and explicit teardown may converge safely. */
        fun release() {
            if (released.compareAndSet(false, true)) GenerationRetention.release(this)
        }
    }

    internal class HeldLock(
        val channel: FileChannel,
        val lock: FileLock,
        var references: Int = 1,
    )

    private data class RetentionPaths(
        val root: Path,
        val setDirectory: Path,
        val lock: Path,
        val marker: Path,
    )
}

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
