// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val GEO_IP_FILE_NAME = "geoip.dat"
private const val GEO_SITE_FILE_NAME = "geosite.dat"
private const val DAT_SUFFIX = ".dat"

/**
 * Hard cap on all geo downloads needed by one profile generation.
 *
 * Happ stops the process after three minutes (research §4). The cap covers the
 * whole generation rather than resetting for each file, so two slow hosts do
 * not turn this into a six-minute operation.
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
@Suppress("TooManyFunctions") // One lifecycle owns its gates, candidate lookup, validation, swap, and cleanup.
public class RoutingProfileImporter
@Inject
internal constructor(
    private val repository: RoutingRepository,
    private val assets: RuleSetAssets,
    private val geoAssets: GeoAssetRepository,
    private val settings: SettingsRepository,
    private val validator: GeoDataValidator,
    private val downloader: GeoDownloader,
) {
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
                val candidate = localCandidate(url, request.kind, existingId, stored, recordedAssets)
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
    ): ImportOutcome {
        when (repository.decideFor(profile)) {
            RoutingRepository.UpdateDecision.Unchanged -> {
                val failed =
                    repository
                        .observeAllStored()
                        .first()
                        .firstOrNull { it.ruleSet.name == profile.name }
                        ?.assetState == RuleSetAssetState.Failed
                if (!failed) return ImportOutcome.Unchanged
            }
            RoutingRepository.UpdateDecision.Stale -> return ImportOutcome.Stale
            RoutingRepository.UpdateDecision.New,
            RoutingRepository.UpdateDecision.Changed,
            -> Unit
        }

        val id = repository.upsertProfile(profile, sourceKind, subscriptionId)
        val stored =
            repository.stored(id)
                ?: return ImportOutcome.Failed(id, RuleSetAssetFailure.InstallFailed)
        val requested = profile.requestedGeoFiles()

        if (requested.isEmpty()) {
            repository.commitGeneration(id, profile, stored.assetGeneration)
            return activateIfAppropriate(id, verb)
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
            repository.commitGeneration(id, profile, stored.assetGeneration)
            return activateIfAppropriate(id, verb)
        }

        val nextGeneration = stored.assetGeneration + 1
        repository.markAssets(id, RuleSetAssetState.Pending)
        val staged =
            try {
                assets.prepareGeneration(id, nextGeneration)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
        if (staged == null) {
            repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.InstallFailed)
            return ImportOutcome.Failed(id, RuleSetAssetFailure.InstallFailed)
        }

        // Room dispatches independently of runTest's virtual scheduler. Resolve
        // read-only candidates before starting the upstream download clock so a
        // database emission is never mistaken for a three-minute network stall.
        val storedSets = repository.observeAllStored().first()
        val recordedAssets = geoAssets.observeAll().first()
        val preparation = prepareLocalFiles(id, requested, staged, storedSets, recordedAssets)
        if (preparation.failure != null) {
            removeUnpublishedGenerations(id, stored.assetGeneration)
            repository.markAssets(id, RuleSetAssetState.Failed, preparation.failure)
            return ImportOutcome.Failed(id, preparation.failure)
        }
        val materialisation =
            withTimeoutOrNull(GEO_DOWNLOAD_TIMEOUT_MILLIS) {
                Materialisation(downloadFiles(preparation.pendingDownloads, staged))
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

        repository.commitGeneration(id, profile, nextGeneration)
        assets.sweepExcept(id, keep = nextGeneration)
        return activateIfAppropriate(id, verb)
    }

    /** Turns routing off without deleting any stored profile. */
    public suspend fun disableRouting() {
        settings.setActiveRoutingRuleSetId(null)
    }

    /** Deletes one row and generation tree, clearing its active reference when necessary. */
    public suspend fun delete(id: Long) {
        val wasActive = settings.activeRoutingRuleSetId.first() == id
        repository.delete(id)
        assets.removeSet(id)
        if (wasActive) settings.setActiveRoutingRuleSetId(null)
    }

    @Suppress(
        "NestedBlockDepth", // Copy, validation, and fallback download are one ordered per-file decision.
        "ReturnCount", // Each early exit preserves the first specific closed-vocabulary failure.
    )
    private suspend fun prepareLocalFiles(
        setId: Long,
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
                when (val copiedFailure = validate(target, request.kind)) {
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
        requested: List<RequestedGeoFile>,
        staged: File,
    ): RuleSetAssetFailure? {
        for (request in requested) {
            val target = generationTarget(staged, request.fileName)
            val url = request.url ?: return RuleSetAssetFailure.Rejected
            try {
                downloader.download(url, target)
            } catch (error: Exception) {
                error.rethrowIfCancellation()
                return RuleSetAssetFailure.DownloadFailed
            }
            validate(target, request.kind)?.let { return it }
        }
        return null
    }

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
    ): ImportOutcome {
        val activate =
            verb == RoutingVerb.OnAdd ||
                (verb == RoutingVerb.Add && settings.activeRoutingRuleSetId.first() == null)
        return if (activate) {
            settings.setActiveRoutingRuleSetId(id)
            ImportOutcome.Activated(id)
        } else {
            ImportOutcome.Stored(id)
        }
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
