// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import art.yniyniyni.subspace.core.data.db.GeoAssetDao
import art.yniyniyni.subspace.core.data.db.GeoAssetEntity
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.model.GeoValidation
import art.yniyniyni.subspace.core.model.isGeoFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Qualifier

/**
 * Qualifies the directory xray-core reads geo databases from.
 *
 * [GeoAssetRepository] needs the concrete path, but this module must not compute
 * it itself: `art.yniyniyni.subspace.geoAssetDirectory` lives in `:app`,
 * downstream of `:core:data` (§4), and is also the exact value
 * `installGeoAssetPath` points `XRAY_LOCATION_ASSET` at — the two must never
 * disagree, so `:app`'s `GeoModule` is the only place allowed to supply it. Hilt
 * aggregates every `@InstallIn(SingletonComponent::class)` module at the app
 * component, so `DataModule.geoAssetRepository` can depend on a binding declared
 * in `:app` without `:core:data` gaining a Gradle dependency on it.
 *
 * Follows the qualifier precedent at `core/network/.../di/AppVersion.kt`: a bare
 * `File` has no type of its own to distinguish "the geo root" from any other
 * file Hilt could be asked to provide.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class GeoAssetRoot

/**
 * Fetches one geo database's bytes to [target], returning its size and SHA-256 digest.
 *
 * [GeoAssetRepository]'s constructor already takes exactly this shape as a plain
 * suspend lambda rather than a `GeoFileFetcher` directly, so the repository
 * stays testable without a real HTTP client — the same reason its `clock` is a
 * `() -> Long` rather than a `System.currentTimeMillis()` call. [GeoDownloader]
 * names that shape as a SAM Hilt can bind. Its production implementation lives
 * in `DataModule.geoDownloader`, inside this module rather than `:app`:
 * `:core:network` is `:core:data`'s own I/O boundary (§4,
 * `checkModuleBoundaries`), and translating `GeoDownloadOutcome`/
 * `GeoFetchFailure` into this narrower contract here is the same seam
 * `SubscriptionSyncFailure` already uses for the subscription pipeline, not a
 * new pattern.
 */
public fun interface GeoDownloader {
    public suspend fun download(
        url: String,
        target: File,
    ): Pair<Long, String>
}

/** How long an installed file is considered fresh for a scheduled refresh (§A.3.1). */
internal const val GEO_REFRESH_INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000

/** What to install, and where from. */
public data class GeoInstallRequest(
    /** The name Xray will look for — `geoip.dat`, `geosite.dat`, or a custom name. */
    val fileName: String,
    val sourceUrl: String,
    val geoType: GeoDataKind,
)

/** The outcome of one install attempt. */
public enum class GeoInstallResult {
    Installed,

    /** The download failed. The previous file, if any, is untouched. */
    DownloadFailed,

    /** The bytes arrived but were not a geo database of the declared kind. */
    Rejected,

    /**
     * The bytes arrived and validated, but staging, publication or recording
     * them failed.
     *
     * Distinct from [DownloadFailed] because §A.4.1 counts telling the user the
     * wrong thing as worse than telling them nothing: "download failed" sends
     * them to check their connection when the network did its job and the
     * device's storage did not. [InstalledGeoAsset.lastFailure] carries which of
     * the local steps it was.
     */
    InstallFailed,
}

/**
 * The only non-validation failure markers persisted by [GeoAssetRepository].
 *
 * These deliberately describe the operation, rather than exposing arbitrary
 * exception implementation names or messages in Room. Validation rejections
 * use the fixed [GeoValidation] enum names instead.
 */
internal enum class GeoAssetFailure {
    InvalidFileName,
    DownloadFailed,
    InstallFailed,
    RecoveryFailed,
}

/** An installed or attempted geo database, as the UI sees it. */
public data class InstalledGeoAsset(
    val fileName: String,
    val sourceUrl: String,
    val geoType: GeoDataKind,
    val sizeBytes: Long?,
    val installedAt: Long?,
    val lastAttemptedAt: Long?,
    /** A closed-vocabulary failure name, or null. §A.3.1's persistent error marker. */
    val lastFailure: String?,
)

/**
 * Downloads, validates and installs geo databases.
 *
 * ## The install sequence, and why the order matters
 *
 * ```
 * 1. stream    → <root>/staging/<operation>/<name>.dat
 * 2. validate  → GeoDataValidator, which also writes <name>.json
 * 3. fsync     staged `.dat` and `.json`
 * 4. move      staging/<operation>/<name>.json → <root>/<name>.json
 * 5. move      staging/<operation>/<name>.dat  → <root>/<name>.dat
 * 6. record    → Room, rolling both files back from same-filesystem backups if this fails
 * ```
 *
 * The `.dat` renames last. Same-filesystem rename is atomic, so until it
 * lands nothing has changed for the core. A torn state can only ever show a
 * newer category list beside an older database, which is cosmetic. Doing it the
 * other way round would leave a window where the core could load a database the
 * editor cannot describe.
 *
 * Validation runs against staging. An HTTP 200 proves nothing — a captive
 * portal or an outage page is valid HTTP and invalid protobuf — and validating
 * in place would mean a corrupt download had already destroyed a working file.
 *
 * A running core is unaffected by any of this. Geo data is read into memory
 * during `core.New`, so replacing the file changes nothing until the next
 * connect. The UI must say so rather than implying a live swap.
 *
 * [download] and [clock] are injected as functions rather than as a
 * `GeoFileFetcher` and a `System.currentTimeMillis()` call so this class is
 * testable without the network — the same indirection, and the same reason, as
 * `ConnectionRecorder` in `:service`.
 */
@Suppress("TooManyFunctions") // The durability steps stay local to the filesystem boundary they protect.
public class GeoAssetRepository
internal constructor(
    private val dao: GeoAssetDao,
    private val validator: GeoDataValidator,
    private val root: File,
    private val download: suspend (url: String, target: File) -> Pair<Long, String>,
    private val clock: () -> Long,
    private val copyForRollback: (source: File, target: File) -> Unit = { source, target ->
        Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING)
    },
) {
    /** Every recorded geo asset, installed or merely attempted. */
    public fun observeAll(): Flow<List<InstalledGeoAsset>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** Where the live `.dat` files sit. `XRAY_LOCATION_ASSET` points here (Part 2). */
    public fun geoDirectory(): File = root

    /** The files currently on disk. The activation gate's other input (spec §4.3). */
    public suspend fun installedFileNames(): Set<String> =
        withContext(Dispatchers.IO) {
            dao
                .installedFileNames()
                .filter(::isSafeFileName)
                .filter { fileName -> File(root, fileName).isFile }
                .toSet()
        }

    /**
     * Whether a scheduled refresh should fetch [fileName] again.
     *
     * Not consulted by [install]: a manual "Update now" always runs. The cap
     * exists to stop a chatty profile hammering a CDN, not to tell the device's
     * owner no (§A.5).
     */
    public suspend fun isDueForRefresh(
        fileName: String,
        nowMillis: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (!isSafeFileName(fileName) || !File(root, fileName).isFile) return@withContext true
            val installedAt = dao.byFileName(fileName)?.installedAt ?: return@withContext true
            nowMillis - installedAt >= GEO_REFRESH_INTERVAL_MILLIS
        }

    /**
     * Runs the full sequence for one file. Never throws (§10.4).
     *
     * On ordinary failures the previous installed DAT/JSON pair is left exactly
     * as it was. A per-name OS file lock covers staging through the Room record,
     * so this remains true when separate app processes update the same asset.
     */
    public suspend fun install(request: GeoInstallRequest): GeoInstallResult =
        withContext(Dispatchers.IO) {
            if (!isSafeFileName(request.fileName)) {
                recordFailure(request, GeoAssetFailure.InvalidFileName)
                return@withContext GeoInstallResult.Rejected
            }
            installMutexes
                .computeIfAbsent(request.fileName) { Mutex() }
                .withLock { installWithFileLock(request) }
        }

    /**
     * Keeps an OS lock for the whole operation, not only the final rename.
     *
     * The lock file intentionally remains on disk after release: deleting it
     * while another process has already opened it would let future callers lock
     * a different inode. Releasing the [java.nio.channels.FileLock] and closing
     * its channel are the cleanup operations that make it available again.
     */
    @Suppress(
        "ReturnCount", // Lock acquisition has two distinct, persistent failure exits.
        "TooGenericExceptionCaught", // Filesystem providers may wrap lock/open failures differently.
    )
    private suspend fun installWithFileLock(request: GeoInstallRequest): GeoInstallResult {
        val lockChannel =
            try {
                val lockDirectory = File(root, LOCK_DIR)
                Files.createDirectories(lockDirectory.toPath())
                FileChannel.open(
                    File(lockDirectory, "${request.fileName}$LOCK_SUFFIX").toPath(),
                    CREATE,
                    WRITE,
                )
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                recordFailure(request, GeoAssetFailure.InstallFailed)
                return GeoInstallResult.InstallFailed
            }

        try {
            val lock =
                try {
                    lockChannel.lock()
                } catch (error: Exception) {
                    error.rethrowIfCancellation()
                    recordFailure(request, GeoAssetFailure.InstallFailed)
                    return GeoInstallResult.InstallFailed
                }
            try {
                return installSafely(request)
            } finally {
                runCatching { lock.release() }
            }
        } finally {
            runCatching { lockChannel.close() }
        }
    }

    @Suppress(
        "LongMethod", // The linear install sequence documents the durability boundary it protects.
        "ReturnCount", // Each distinct failure must persist its own closed-vocabulary marker.
        "TooGenericExceptionCaught", // Validator/download callbacks may throw arbitrary caller exceptions.
    )
    private suspend fun installSafely(request: GeoInstallRequest): GeoInstallResult {
        val stagingRoot = File(root, STAGING_DIR)
        val stagingDir =
            try {
                Files.createDirectories(stagingRoot.toPath())
                Files.createTempDirectory(stagingRoot.toPath(), STAGING_PREFIX).toFile()
            } catch (_: IOException) {
                recordFailure(request, GeoAssetFailure.InstallFailed)
                return GeoInstallResult.InstallFailed
            }
        val baseName = request.fileName.removeSuffix(DAT_SUFFIX)
        val stagedDat = File(stagingDir, request.fileName)
        val stagedJson = File(stagingDir, "$baseName$JSON_SUFFIX")
        var retainStaging = false

        return try {
            val (bytes, digest) =
                try {
                    download(request.sourceUrl, stagedDat)
                } catch (error: Exception) {
                    error.rethrowIfCancellation()
                    recordFailure(request, GeoAssetFailure.DownloadFailed)
                    return GeoInstallResult.DownloadFailed
                }

            val validation =
                try {
                    validator.validate(stagingDir, baseName, request.geoType)
                } catch (error: Exception) {
                    error.rethrowIfCancellation()
                    recordFailure(request, GeoAssetFailure.InstallFailed)
                    return GeoInstallResult.InstallFailed
                }
            if (validation != GeoValidation.Valid) {
                recordValidationFailure(request, validation)
                return GeoInstallResult.Rejected
            }

            val publication =
                try {
                    publish(stagedDat, stagedJson, request.fileName, "$baseName$JSON_SUFFIX", stagingDir)
                } catch (_: RollbackFailedException) {
                    retainStaging = true
                    recordFailure(request, GeoAssetFailure.RecoveryFailed)
                    return GeoInstallResult.InstallFailed
                } catch (error: Exception) {
                    error.rethrowIfCancellation()
                    recordFailure(request, GeoAssetFailure.InstallFailed)
                    return GeoInstallResult.InstallFailed
                }

            try {
                record(request, failure = null, bytes = bytes, digest = digest, installed = true)
                GeoInstallResult.Installed
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                retainStaging = !rollback(publication)
                recordFailure(
                    request,
                    if (retainStaging) GeoAssetFailure.RecoveryFailed else GeoAssetFailure.InstallFailed,
                )
                return GeoInstallResult.InstallFailed
            }
        } finally {
            // A failed rollback keeps its forced backups for recovery. This
            // operation alone owns the directory, so normal cleanup cannot
            // race a second install or delete its staged bytes.
            if (!retainStaging) stagingDir.deleteRecursively()
        }
    }

    /**
     * Forces staged files and recoverable backups, then publishes JSON before the live database.
     *
     * The backups live in this operation's private staging directory, which is on the same
     * filesystem as [root]. They remain available until the Room success record commits.
     */
    @Suppress("TooGenericExceptionCaught") // Atomic moves can throw provider-specific runtime exceptions.
    private fun publish(
        stagedDat: File,
        stagedJson: File,
        datName: String,
        jsonName: String,
        stagingDir: File,
    ): Publication {
        checkStagedFile(stagedDat)
        checkStagedFile(stagedJson)
        forceToDisk(stagedDat)
        forceToDisk(stagedJson)
        val publication =
            Publication(
                dat = backupLiveFile(File(root, datName), File(stagingDir, "$datName$BACKUP_SUFFIX")),
                json = backupLiveFile(File(root, jsonName), File(stagingDir, "$jsonName$BACKUP_SUFFIX")),
            )
        try {
            checkedAtomicMove(stagedJson, publication.json.live)
            checkedAtomicMove(stagedDat, publication.dat.live)
            return publication
        } catch (error: Exception) {
            if (!rollback(publication)) throw RollbackFailedException(error)
            throw error
        }
    }

    /** Copies a prior live file into a forced, same-filesystem rollback backup. */
    private fun backupLiveFile(live: File, backup: File): LiveFileBackup {
        if (!live.exists()) return LiveFileBackup(live, backup = null)
        if (!live.isFile) throw IOException("live asset is not a regular file")
        Files.copy(live.toPath(), backup.toPath(), REPLACE_EXISTING)
        forceToDisk(backup)
        return LiveFileBackup(live, backup)
    }

    /**
     * Restores the exact previous DAT/JSON pair after a failed publish or Room record.
     *
     * The backup is copied to a short-lived restore file before its atomic
     * replacement, rather than moved. If restoration fails, the original
     * forced backup remains in staging and its operation directory is retained.
     */
    private fun rollback(publication: Publication): Boolean {
        val jsonRestored = runCatching { rollback(publication.json) }.isSuccess
        val datRestored = runCatching { rollback(publication.dat) }.isSuccess
        return jsonRestored && datRestored
    }

    private fun rollback(backup: LiveFileBackup) {
        if (backup.backup == null) {
            Files.deleteIfExists(backup.live.toPath())
        } else {
            val restore = File(backup.backup.parentFile, "${backup.backup.name}$RESTORE_SUFFIX")
            copyForRollback(backup.backup, restore)
            forceToDisk(restore)
            checkedAtomicMove(restore, backup.live)
        }
    }

    private fun checkStagedFile(file: File) {
        if (!file.isFile) throw IOException("staged asset missing")
    }

    private fun forceToDisk(file: File) {
        FileChannel.open(file.toPath(), WRITE).use { channel -> channel.force(true) }
    }

    private fun checkedAtomicMove(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        if (!target.isFile || source.exists()) throw IOException("atomic asset move failed")
    }

    private suspend fun record(
        request: GeoInstallRequest,
        failure: String?,
        bytes: Long? = null,
        digest: String? = null,
        installed: Boolean = false,
    ) {
        val now = clock()
        val previous = dao.byFileName(request.fileName)
        dao.upsert(
            GeoAssetEntity(
                fileName = request.fileName,
                sourceUrl = request.sourceUrl,
                geoType = request.geoType.name,
                sha256 = digest ?: previous?.sha256,
                sizeBytes = bytes ?: previous?.sizeBytes,
                installedAt = if (installed) now else previous?.installedAt,
                lastAttemptedAt = now,
                lastFailure = failure,
            ),
        )
    }

    @Suppress(
        "SwallowedException", // A database failure cannot itself be persisted; never throw from install.
        "TooGenericExceptionCaught", // Room may wrap its failures in implementation-specific exceptions.
    )
    private suspend fun recordFailure(
        request: GeoInstallRequest,
        failure: GeoAssetFailure,
    ) {
        try {
            record(request, failure = failure.name)
        } catch (error: Exception) {
            error.rethrowIfCancellation()
        }
    }

    @Suppress(
        "SwallowedException", // A database failure cannot itself be persisted; never throw from install.
        "TooGenericExceptionCaught", // Room may wrap its failures in implementation-specific exceptions.
    )
    private suspend fun recordValidationFailure(
        request: GeoInstallRequest,
        failure: GeoValidation,
    ) {
        try {
            record(request, failure = failure.name)
        } catch (error: Exception) {
            error.rethrowIfCancellation()
        }
    }

    private fun Exception.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    private fun GeoAssetEntity.toModel(): InstalledGeoAsset =
        InstalledGeoAsset(
            fileName = fileName,
            sourceUrl = sourceUrl,
            geoType = runCatching { GeoDataKind.valueOf(geoType) }.getOrNull() ?: GeoDataKind.DOMAIN,
            sizeBytes = sizeBytes,
            installedAt = installedAt,
            lastAttemptedAt = lastAttemptedAt,
            lastFailure = lastFailure,
        )

    /** A live file and, if it existed, its forced rollback copy in staging. */
    private data class LiveFileBackup(
        val live: File,
        val backup: File?,
    )

    /** Both files which must move together when rolling back a failed record. */
    private data class Publication(
        val dat: LiveFileBackup,
        val json: LiveFileBackup,
    )

    /** Signals that an attempted rollback retained backups which need recovery. */
    private class RollbackFailedException(cause: Exception) : IOException(cause)

    /**
     * Was `private companion object` until fix round 1 review (Task 16): [DAT_SUFFIX] and
     * [JSON_SUFFIX] are the authority for the `.dat` → `.json` sidecar naming convention this
     * class's own install sequence documents (KDoc above: "move staging/.../<name>.json →
     * <root>/<name>.json" before the `.dat`) — `:feature:routing`'s rule set editor needs that
     * exact swap to find a built-in database's category sidecar, and re-declaring the two
     * literals there instead of reading them from here is the drift [isGeoFileName]'s own KDoc
     * already warns about for the filename grammar. Every other member here stays `private`
     * individually; only the two suffixes are part of this class's public surface now.
     */
    public companion object {
        public const val DAT_SUFFIX: String = ".dat"
        public const val JSON_SUFFIX: String = ".json"

        private const val STAGING_DIR = "staging"
        private const val LOCK_DIR = "locks"
        private const val STAGING_PREFIX = "geo-"
        private const val BACKUP_SUFFIX = ".previous"
        private const val RESTORE_SUFFIX = ".restore"
        private const val LOCK_SUFFIX = ".lock"

        private val installMutexes = ConcurrentHashMap<String, Mutex>()

        /**
         * The grammar `ext:` routing entries accept, from `:core:model`.
         *
         * Deliberately not a second copy of the expression: `Redaction` relies on
         * the same grammar for its §5.6 exemption, so a local edit here that did
         * not reach there would fail unsafe.
         */
        private fun isSafeFileName(fileName: String): Boolean = isGeoFileName(fileName)
    }
}
