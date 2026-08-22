// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import android.database.sqlite.SQLiteConstraintException
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.model.GeoValidation
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import art.yniyniyni.subspace.core.model.requiredGeoFiles
import art.yniyniyni.subspace.core.parser.routing.RoutingVerb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val GEO_IP_FILE_NAME = "geoip.dat"
private const val GEO_SITE_FILE_NAME = "geosite.dat"
private const val DAT_SUFFIX = ".dat"
private const val MISSING_SUBSCRIPTION_RULE_SET_ID = 0L

/** The published generation of a row whose geo files come from the shared catalogue. */
private const val NO_OWN_GENERATION = 0L

/** The first generation a row can own; generations are positive by construction. */
private const val FIRST_GENERATION = 1L

/**
 * Coroutine deadline around the complete materialisation of one profile generation.
 *
 * Network suspensions and chunked filesystem work observe cancellation cooperatively. Production
 * validation reaches synchronous libXray JNI, which cannot be forcibly interrupted; if that native
 * call hangs, this deadline is observed only after JNI returns. A strict native hard cap requires
 * process isolation and a core API refactor outside M6.
 */
public const val GEO_DOWNLOAD_TIMEOUT_MILLIS: Long = 3 * 60 * 1000L

/** The observable result of applying one approved routing profile. */
public sealed interface ImportOutcome {
    /** The profile landed and became the active routing rule set. */
    public data class Activated(
        public val id: Long,
    ) : ImportOutcome

    /** The profile landed without displacing the active routing rule set. */
    public data class Stored(
        public val id: Long,
    ) : ImportOutcome

    /** Its canonical fingerprint was already stored, so no write or I/O occurred. */
    public data object Unchanged : ImportOutcome

    /** Its changed content did not pass the profile timestamp gate. */
    public data object Stale : ImportOutcome

    /** The row remains stored, but no new generation became live. */
    public data class Failed(
        /** Persisted row id, or zero only when the subscription disappeared before row creation. */
        public val id: Long,
        public val failure: RuleSetAssetFailure,
    ) : ImportOutcome
}

/** One geo file the review sheet may need to disclose before approval. */
public data class GeoFilePreview(
    public val fileName: String,
    public val url: String,
    public val approximateBytes: Long?,
    public val alreadyOnDevice: Boolean,
) {
    /** §5.6: provider-controlled URLs are rendered in UI but never emitted to logs. */
    override fun toString(): String =
        "GeoFilePreview(fileName=$fileName, url=<redacted>, " +
            "approximateBytes=$approximateBytes, alreadyOnDevice=$alreadyOnDevice)"
}

/** Read-only information consumed by the import confirmation sheet. */
public data class ImportPreview(
    public val decision: RoutingRepository.UpdateDecision,
    public val profile: RoutingProfile,
    public val replacesExisting: Boolean,
    public val willActivate: Boolean,
    public val geoFiles: List<GeoFilePreview>,
)

/**
 * Applies approved routing profiles without exposing a half-materialised generation.
 *
 * The ordered lifecycle is the contract: decide before I/O, materialise generation
 * `n + 1` while `n` remains live, publish rules and generation in one Room write,
 * and only then sweep `n` (spec §7.4).
 */
@Singleton
@Suppress(
    "LongParameterList", // The injected constructor names each lifecycle boundary; bundling them would hide ownership.
    "TooManyFunctions", // One lifecycle owns its gates, candidate lookup, validation, swap, and cleanup.
)
public class RoutingProfileImporter
@Inject
internal constructor(
    private val database: SubspaceDatabase,
    private val repository: RoutingRepository,
    private val assets: RuleSetAssets,
    private val geoAssets: GeoAssetRepository,
    private val settings: SettingsRepository,
    private val validator: GeoDataValidator,
    private val downloader: GeoDownloader,
    private val deletion: RoutingProfileDeletion,
    private val progress: GeoDownloadProgressRegistry,
) {
    private var materialisationTimeoutMillis: Long = GEO_DOWNLOAD_TIMEOUT_MILLIS
    private var beforeGenerationCommit: suspend (setId: Long, generation: Long) -> Unit = { _, _ -> }

    /** Instrumented seams for the real-time deadline and exact pre-publication boundary. */
    internal constructor(
        database: SubspaceDatabase,
        repository: RoutingRepository,
        assets: RuleSetAssets,
        geoAssets: GeoAssetRepository,
        settings: SettingsRepository,
        validator: GeoDataValidator,
        downloader: GeoDownloader,
        deletion: RoutingProfileDeletion,
        progress: GeoDownloadProgressRegistry,
        materialisationTimeoutMillis: Long,
        beforeGenerationCommit: suspend (setId: Long, generation: Long) -> Unit = { _, _ -> },
    ) : this(database, repository, assets, geoAssets, settings, validator, downloader, deletion, progress) {
        require(materialisationTimeoutMillis > 0) { "Materialisation timeout must be positive" }
        this.materialisationTimeoutMillis = materialisationTimeoutMillis
        this.beforeGenerationCommit = beforeGenerationCommit
    }

    /**
     * Builds confirmation data without staging, downloading, validating, or writing.
     *
     * The brief's API carries no [RoutingVerb], so [ImportPreview.willActivate]
     * describes `/add`: true only when no rule set is currently active. `/onadd` is
     * unconditionally activating and its caller already owns that verb.
     */
    public suspend fun preview(profile: RoutingProfile): ImportPreview {
        val decision = repository.decideFor(profile)
        val stored = repository.observeAllStored().first()
        val existingId = stored.firstOrNull { it.ruleSet.name == profile.name }?.ruleSet?.id
        val recordedAssets = geoAssets.observeAll().first()
        val requested = profile.requestedGeoFiles()
        val previews =
            requested.mapNotNull { request ->
                val url = request.url ?: return@mapNotNull null
                val candidate = previewCandidate(url, request.kind, existingId, stored, recordedAssets)
                GeoFilePreview(
                    fileName = request.fileName,
                    url = url,
                    approximateBytes = candidate?.approximateBytes,
                    alreadyOnDevice = candidate != null,
                )
            }
        return ImportPreview(
            decision = decision,
            profile = profile,
            replacesExisting = existingId != null,
            willActivate = settings.activeRoutingRuleSetId.first() == null,
            geoFiles = previews,
        )
    }

    /**
     * Applies one profile after the caller's confirmation gate.
     *
     * Early returns are intentional ordering barriers: unchanged/stale inputs
     * cannot reach disk, and failed staging cannot reach the atomic publication.
     */
    @Suppress(
        "CyclomaticComplexMethod", // Explicit gates prevent stale, partial, or failed assets from reaching publication.
        "LongMethod", // Keeping the gate → stage → swap → sweep sequence linear makes its order reviewable.
        "ReturnCount", // Every early return protects a distinct no-I/O or no-publication guarantee.
    )
    public suspend fun apply(
        profile: RoutingProfile,
        verb: RoutingVerb,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
    ): ImportOutcome =
        RoutingProfileProcessCoordinator.withImport(profile.name, subscriptionId) { locks ->
            applySerialized(profile, verb, sourceKind, subscriptionId, locks)
        }

    @Suppress(
        "CyclomaticComplexMethod", // Explicit gates prevent stale, partial, or failed assets from reaching publication.
        "LongMethod", // Keeping the gate → stage → swap → sweep sequence linear makes its order reviewable.
        "ReturnCount", // Every early return protects a distinct no-I/O or no-publication guarantee.
    )
    private suspend fun applySerialized(
        profile: RoutingProfile,
        verb: RoutingVerb,
        sourceKind: RoutingSourceKind,
        subscriptionId: Long?,
        locks: RoutingProfileProcessCoordinator.ProfileLocks,
    ): ImportOutcome {
        if (subscriptionId != null && database.subscriptionDao().subscription(subscriptionId) == null) {
            return ImportOutcome.Failed(MISSING_SUBSCRIPTION_RULE_SET_ID, RuleSetAssetFailure.Rejected)
        }
        when (repository.decideFor(profile)) {
            RoutingRepository.UpdateDecision.Unchanged -> return ImportOutcome.Unchanged
            RoutingRepository.UpdateDecision.Stale -> return ImportOutcome.Stale
            RoutingRepository.UpdateDecision.New,
            RoutingRepository.UpdateDecision.Changed,
            -> Unit
        }

        val id =
            try {
                repository.upsertImportedProfile(profile, sourceKind, subscriptionId, locks)
            } catch (error: SQLiteConstraintException) {
                // A cross-process subscription deletion can land after the pre-row check despite
                // this process's lifecycle lock. Map only that vanished-parent case; uniqueness
                // and every unrelated constraint failure retain their original exception.
                if (subscriptionId != null && database.subscriptionDao().subscription(subscriptionId) == null) {
                    return ImportOutcome.Failed(MISSING_SUBSCRIPTION_RULE_SET_ID, RuleSetAssetFailure.Rejected)
                }
                throw error
            }
        val stored =
            repository.stored(id)
                ?: return ImportOutcome.Failed(id, RuleSetAssetFailure.InstallFailed)
        val requested = profile.requestedGeoFiles()

        if (requested.isEmpty()) {
            return publishWithoutOwnGeneration(id, profile, stored, verb)
        }

        if (requested.any { it.fileName !in suppliedFileNames }) {
            repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.Rejected)
            return ImportOutcome.Failed(id, RuleSetAssetFailure.Rejected)
        }

        if (!profile.hasOwnGeoSources()) {
            val installed = geoAssets.installedFileNames()
            if (requested.any { it.fileName !in installed }) {
                repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.Rejected)
                return ImportOutcome.Failed(id, RuleSetAssetFailure.Rejected)
            }
            return publishWithoutOwnGeneration(id, profile, stored, verb)
        }

        val nextGeneration = stored.assetGeneration + 1
        var publicationCommitted = false
        try {
            val materialisation =
                withContext(Dispatchers.Default) {
                    // track() registers *this* coroutine, so the row's cancel
                    // stops the real download rather than a proxy for it, and
                    // the running bar disappears however the block ends.
                    progress.track(id) {
                        withTimeoutOrNull(materialisationTimeoutMillis) {
                            Materialisation(materialise(id, nextGeneration, requested))
                        }
                    }
                }
            val failure =
                if (materialisation == null) {
                    RuleSetAssetFailure.TimedOut
                } else {
                    materialisation.failure
                }
            if (failure != null) {
                removeUnpublishedGenerations(id, stored.assetGeneration)
                repository.markAssets(id, RuleSetAssetState.Failed, failure)
                return ImportOutcome.Failed(id, failure)
            }

            beforeGenerationCommit(id, nextGeneration)
            repository.commitGeneration(id, profile, nextGeneration)
            publicationCommitted = true
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                reconcileCancelledGeneration(
                    setId = id,
                    oldGeneration = stored.assetGeneration,
                    newGeneration = nextGeneration,
                    publicationCommitted = publicationCommitted,
                )
            }
            throw error
        }

        assets.sweepExcept(id, keep = nextGeneration)
        return activateIfAppropriate(id, verb)
    }

    /**
     * Publishes a profile that reads from the shared catalogue, and reclaims any
     * generation tree the row used to own.
     *
     * Generation **0**, not the row's previous number: a row that updates from
     * own-source rules to literal-only (or to rules the shared catalogue already
     * satisfies) stops owning a generation, and leaving the old number published
     * would make [StoredRuleSet.usesOwnGeneration] keep pointing the service at
     * a directory validated for the *previous* profile.
     *
     * The sweep runs strictly **after** publication. Deleting first would pull
     * files out from under a service startup still holding the old generation's
     * lease; `RuleSetAssets.removeSet` defers through `GenerationRetention` for
     * exactly that reason, so a leased generation is reclaimed when released
     * rather than never.
     */
    private suspend fun publishWithoutOwnGeneration(
        id: Long,
        profile: RoutingProfile,
        stored: StoredRuleSet,
        verb: RoutingVerb,
    ): ImportOutcome {
        repository.commitGeneration(id, profile, NO_OWN_GENERATION)
        if (stored.assetGeneration > NO_OWN_GENERATION) assets.removeSet(id)
        return activateIfAppropriate(id, verb)
    }

    /** Everything that can delay generation readiness shares one deadline. */
    private suspend fun materialise(
        setId: Long,
        generation: Long,
        requested: List<RequestedGeoFile>,
    ): RuleSetAssetFailure? {
        val staged =
            try {
                assets.prepareGeneration(setId, generation)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        if (staged == null) return RuleSetAssetFailure.InstallFailed
        val storedSets = repository.observeAllStored().first()
        val recordedAssets = geoAssets.observeAll().first()
        val preparation = prepareLocalFiles(setId, generation, requested, staged, storedSets, recordedAssets)
        return preparation.failure ?: downloadFiles(setId, generation, preparation.pendingDownloads, staged)
    }

    /** Turns routing off without deleting any stored profile. */
    public suspend fun disableRouting() {
        RoutingProfileProcessCoordinator.withSettings {
            settings.setActiveRoutingRuleSetId(null)
        }
    }

    /** Deletes one row and generation tree, clearing its active reference when necessary. */
    public suspend fun delete(id: Long) = deletion.deleteRuleSet(id)

    /**
     * Copies [id] into an editable rule set under [name], carrying its assets.
     *
     * Spec §4.2's "Duplicate and edit". The copy must own its own generation
     * rather than fall back to the shared catalogue: an imported profile's
     * `geosite.dat` came from *its* upstream, so a copy that kept
     * `geosite:cn` rules while silently reading a same-named shared file would
     * route on different data than the profile it claims to duplicate — and
     * would simply not activate when the shared root lacks the name.
     *
     * Assets are copied before publication and the rules and generation are
     * published together, so a failed copy leaves no half-built row. The copy
     * carries no provenance, which is the point: the provider does not own it
     * and the next sync will not overwrite it.
     */
    // ReturnCount: missing original, shared-catalogue copy, failed copy and
    // published copy are four distinct terminal outcomes, and flattening them
    // would hide which of them leaves the row without assets.
    @Suppress("ReturnCount")
    public suspend fun duplicate(
        id: Long,
        name: String,
    ): Long? {
        val original = repository.stored(id) ?: return null
        val copyId = repository.upsert(original.ruleSet.copy(id = 0L, name = name))
        if (!original.usesOwnGeneration) return copyId
        val sourceDir = assets.generationDir(id, original.assetGeneration)
        val copied =
            runCatching {
                val staged = assets.prepareGeneration(copyId, FIRST_GENERATION)
                original.ruleSet.requiredGeoFiles().all { fileName ->
                    assets.copyLocally(File(sourceDir, fileName), File(staged, fileName))
                }
            }.getOrDefault(false)
        if (!copied) {
            // The row exists and is editable; it simply has no assets of its own
            // yet. Marking it Failed says that on the row rather than leaving a
            // copy that looks complete and cannot activate.
            repository.markAssets(copyId, RuleSetAssetState.Failed, RuleSetAssetFailure.InstallFailed)
            return copyId
        }
        repository.publishCopiedGeneration(copyId, FIRST_GENERATION)
        return copyId
    }

    @Suppress(
        "NestedBlockDepth", // Copy, validation, and fallback download are one ordered per-file decision.
        "ReturnCount", // Each early exit preserves the first specific closed-vocabulary failure.
    )
    private suspend fun prepareLocalFiles(
        setId: Long,
        generation: Long,
        requested: List<RequestedGeoFile>,
        staged: File,
        stored: List<StoredRuleSet>,
        recordedAssets: List<InstalledGeoAsset>,
    ): LocalPreparation {
        val pendingDownloads = mutableListOf<RequestedGeoFile>()
        for (request in requested) {
            val target = generationTarget(staged, request.fileName)
            val source =
                if (request.url == null) {
                    sharedFile(request.fileName, request.kind, recordedAssets)
                } else {
                    localCandidate(request.url, request.kind, setId, stored, recordedAssets)?.file
                }

            if (source != null && assets.copyLocally(source, target)) {
                when (val copiedFailure = validateAndRecord(setId, generation, target, request.kind)) {
                    null -> continue
                    RuleSetAssetFailure.Rejected -> {
                        if (request.url == null) return LocalPreparation(failure = copiedFailure)
                    }
                    else -> return LocalPreparation(failure = copiedFailure)
                }
            } else if (request.url == null) {
                return LocalPreparation(failure = RuleSetAssetFailure.Rejected)
            }
            pendingDownloads += request
        }
        return LocalPreparation(pendingDownloads = pendingDownloads)
    }

    @Suppress(
        "ReturnCount", // A generation stops on its first missing URL, fetch failure, or validation failure.
        "TooGenericExceptionCaught", // GeoDownloader implementations may throw arbitrary transport exceptions.
    )
    private suspend fun downloadFiles(
        setId: Long,
        generation: Long,
        requested: List<RequestedGeoFile>,
        staged: File,
    ): RuleSetAssetFailure? {
        for (request in requested) {
            val target = generationTarget(staged, request.fileName)
            val url = request.url ?: return RuleSetAssetFailure.Rejected
            try {
                downloader.download(url, target) { downloadedBytes, totalBytes ->
                    progress.report(setId, request.fileName, downloadedBytes, totalBytes)
                }
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                return RuleSetAssetFailure.DownloadFailed
            }
            validateAndRecord(setId, generation, target, request.kind)?.let { return it }
        }
        return null
    }

    private suspend fun validateAndRecord(
        setId: Long,
        generation: Long,
        file: File,
        kind: GeoDataKind,
    ): RuleSetAssetFailure? =
        validate(file, kind)
            ?: if (assets.recordValidatedFile(setId, generation, file.name)) {
                null
            } else {
                RuleSetAssetFailure.InstallFailed
            }

    /**
     * Production validation enters synchronous libXray JNI. Coroutine cancellation is observed
     * after that call returns; M6 deliberately does not add process isolation solely to pre-empt it.
     */
    @Suppress("TooGenericExceptionCaught") // GeoDataValidator is an injected boundary with arbitrary implementations.
    private suspend fun validate(
        file: File,
        kind: GeoDataKind,
    ): RuleSetAssetFailure? {
        val directory = file.parentFile ?: return RuleSetAssetFailure.InstallFailed
        return try {
            when (validator.validate(directory, file.name.removeSuffix(DAT_SUFFIX), kind)) {
                GeoValidation.Valid -> null
                GeoValidation.Unreadable,
                GeoValidation.NotGeoData,
                -> RuleSetAssetFailure.Rejected
            }
        } catch (error: Exception) {
            error.rethrowIfCancellation()
            RuleSetAssetFailure.InstallFailed
        }
    }

    private suspend fun activateIfAppropriate(
        id: Long,
        verb: RoutingVerb,
    ): ImportOutcome =
        RoutingProfileProcessCoordinator.withSettings {
            val activated =
                when (verb) {
                    RoutingVerb.OnAdd -> {
                        settings.setActiveRoutingRuleSetId(id)
                        true
                    }
                    RoutingVerb.Add -> settings.activateRoutingRuleSetIfNone(id)
                    RoutingVerb.Off -> false
                }
            if (activated) ImportOutcome.Activated(id) else ImportOutcome.Stored(id)
        }

    private suspend fun removeUnpublishedGenerations(
        setId: Long,
        liveGeneration: Long,
    ) {
        if (liveGeneration > 0) {
            assets.sweepExcept(setId, keep = liveGeneration)
        } else {
            assets.removeSet(setId)
        }
    }

    /** Cancellation cleanup consults publication state before deleting either generation. */
    private suspend fun reconcileCancelledGeneration(
        setId: Long,
        oldGeneration: Long,
        newGeneration: Long,
        publicationCommitted: Boolean,
    ) {
        val liveGeneration =
            if (publicationCommitted) {
                newGeneration
            } else {
                repository.stored(setId)?.assetGeneration
            }
        when (liveGeneration) {
            null -> assets.removeSet(setId)
            newGeneration -> assets.sweepExcept(setId, keep = newGeneration)
            oldGeneration -> removeUnpublishedGenerations(setId, oldGeneration)
            else -> if (liveGeneration > 0) assets.sweepExcept(setId, keep = liveGeneration)
        }
    }

    /**
     * Derives every writer/copy target from the freshly prepared generation.
     *
     * Only the two fixed Xray filenames reach this method. The normalized path
     * check still pins the containment invariant explicitly, so broadening the
     * caller later cannot turn [RuleSetAssets.copyLocally] into an arbitrary write.
     */
    private fun generationTarget(
        staged: File,
        fileName: String,
    ): File {
        require(fileName in suppliedFileNames) { "Unsupported routing geo file" }
        val generation = staged.canonicalFile.toPath()
        val target = generation.resolve(fileName).normalize()
        check(target.parent == generation && target.startsWith(generation)) {
            "Routing geo target escaped its generation"
        }
        return target.toFile()
    }

    @Suppress("ReturnCount") // Shared, per-set, and absent are the three ordered candidate outcomes.
    private fun localCandidate(
        url: String,
        kind: GeoDataKind,
        excludedSetId: Long?,
        stored: List<StoredRuleSet>,
        recordedAssets: List<InstalledGeoAsset>,
    ): LocalCandidate? {
        recordedAssets
            .firstOrNull { row ->
                row.sourceUrl == url && row.geoType == kind && row.installedAt != null
            }?.let { row ->
                val file = File(geoAssets.geoDirectory(), row.fileName)
                if (file.isFile) return LocalCandidate(file, row.sizeBytes)
            }

        stored.forEach { row ->
            if (row.ruleSet.id == excludedSetId || row.assetGeneration <= 0) return@forEach
            val matches =
                when (kind) {
                    GeoDataKind.IP -> row.geoIpUrl == url
                    GeoDataKind.DOMAIN -> row.geoSiteUrl == url
                }
            if (matches) {
                val file =
                    File(
                        assets.generationDir(row.ruleSet.id, row.assetGeneration),
                        kind.fileName,
                    )
                if (file.isFile) return LocalCandidate(file, approximateBytes = null)
            }
        }
        return null
    }

    /**
     * Proves reuse from immutable persisted evidence without invoking the sidecar-writing validator.
     *
     * Shared assets use their successful-install digest. Other sets use digest metadata committed
     * inside their live generation. Missing, corrupt, disappearing, or unreadable evidence is a
     * conservative cache miss; apply may still fetch or validate bytes after approval.
     */
    private suspend fun previewCandidate(
        url: String,
        kind: GeoDataKind,
        excludedSetId: Long?,
        stored: List<StoredRuleSet>,
        recordedAssets: List<InstalledGeoAsset>,
    ): LocalCandidate? =
        previewSharedCandidate(url, kind, recordedAssets)
            ?: previewGenerationCandidate(url, kind, excludedSetId, stored)

    private suspend fun previewSharedCandidate(
        url: String,
        kind: GeoDataKind,
        recordedAssets: List<InstalledGeoAsset>,
    ): LocalCandidate? {
        val row =
            recordedAssets.firstOrNull { asset ->
                asset.sourceUrl == url &&
                    asset.geoType == kind &&
                    asset.installedAt != null &&
                    asset.sha256 != null
            }
        if (row != null) {
            val file = File(geoAssets.geoDirectory(), row.fileName)
            if (file.isFile && row.sizeBytes == file.length() && file.sha256ReadOnly() == row.sha256) {
                return LocalCandidate(file, row.sizeBytes)
            }
        }
        return null
    }

    private suspend fun previewGenerationCandidate(
        url: String,
        kind: GeoDataKind,
        excludedSetId: Long?,
        stored: List<StoredRuleSet>,
    ): LocalCandidate? {
        stored.forEach { candidate ->
            if (candidate.ruleSet.id == excludedSetId || candidate.assetGeneration <= 0) return@forEach
            val matches =
                when (kind) {
                    GeoDataKind.IP -> candidate.geoIpUrl == url
                    GeoDataKind.DOMAIN -> candidate.geoSiteUrl == url
                }
            if (matches) {
                assets.verifiedGenerationFile(
                    candidate.ruleSet.id,
                    candidate.assetGeneration,
                    kind.fileName,
                )?.let { return LocalCandidate(it, it.length()) }
            }
        }
        return null
    }

    @Suppress("MagicNumber") // 64 KiB streams large geo databases without retaining them in memory.
    private suspend fun File.sha256ReadOnly(): String? =
        withContext(Dispatchers.IO) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(this@sha256ReadOnly).buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        }

    private fun sharedFile(
        fileName: String,
        kind: GeoDataKind,
        recordedAssets: List<InstalledGeoAsset>,
    ): File? {
        val installed =
            recordedAssets.firstOrNull { row ->
                row.fileName == fileName && row.geoType == kind && row.installedAt != null
            } ?: return null
        return File(geoAssets.geoDirectory(), installed.fileName).takeIf(File::isFile)
    }

    private fun RoutingProfile.requestedGeoFiles(): List<RequestedGeoFile> {
        val required = toRuleSet().requiredGeoFiles()
        return required
            .sortedWith(
                compareBy(
                    { suppliedFileNames.indexOf(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
                    { it },
                ),
            )
            .map { fileName ->
                when (fileName) {
                    GEO_IP_FILE_NAME -> RequestedGeoFile(fileName, geoIpUrl, GeoDataKind.IP)
                    GEO_SITE_FILE_NAME -> RequestedGeoFile(fileName, geoSiteUrl, GeoDataKind.DOMAIN)
                    else -> RequestedGeoFile(fileName, url = null, GeoDataKind.DOMAIN)
                }
            }
    }

    private fun RoutingProfile.hasOwnGeoSources(): Boolean = geoIpUrl != null || geoSiteUrl != null

    private data class RequestedGeoFile(
        val fileName: String,
        val url: String?,
        val kind: GeoDataKind,
    )

    private data class LocalCandidate(
        val file: File,
        val approximateBytes: Long?,
    )

    private data class Materialisation(
        val failure: RuleSetAssetFailure?,
    )

    private data class LocalPreparation(
        val pendingDownloads: List<RequestedGeoFile> = emptyList(),
        val failure: RuleSetAssetFailure? = null,
    )

    private val GeoDataKind.fileName: String
        get() = if (this == GeoDataKind.IP) GEO_IP_FILE_NAME else GEO_SITE_FILE_NAME

    private fun Exception.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }

    private companion object {
        val suppliedFileNames = listOf(GEO_IP_FILE_NAME, GEO_SITE_FILE_NAME)
    }
}
