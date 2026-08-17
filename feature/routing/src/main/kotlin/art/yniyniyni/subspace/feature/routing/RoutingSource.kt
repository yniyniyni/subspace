// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.data.GeoAssetRepository
import art.yniyniyni.subspace.core.data.RoutingRepository
import art.yniyniyni.subspace.core.data.SettingsRepository
import art.yniyniyni.subspace.core.model.BucketField
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `<name>.json` sidecar for [field]'s built-in geo database — swaps
 * [GeoAssetRepository.DAT_SUFFIX] for [GeoAssetRepository.JSON_SUFFIX] on
 * [RoutingEntries.builtInGeoFileName], the exact suffix swap
 * [GeoAssetRepository]'s own install sequence performs when it publishes the
 * two files (KDoc there: JSON before DAT). A standalone top-level function,
 * not a private detail of [BoundRoutingSource.categoriesFor], so a plain JVM
 * test can pin `"geosite.dat" -> "geosite.json"` without needing a real
 * [BoundRoutingSource] (which — like every other member on it — cannot be
 * constructed outside `:core:data`; see this file's own class KDoc).
 */
internal fun categorySidecarFileName(field: BucketField): String {
    val datName = RoutingEntries.builtInGeoFileName(field).removeSuffix(GeoAssetRepository.DAT_SUFFIX)
    return datName + GeoAssetRepository.JSON_SUFFIX
}

/**
 * The [RoutingRepository], [SettingsRepository] and [GeoAssetRepository] slice
 * this feature needs, following
 * [art.yniyniyni.subspace.feature.settings.SettingsSource]'s shape: all three
 * repositories have `internal` constructors scoped to `:core:data` (§3), so
 * this module cannot build real instances of them to test against.
 * [BoundRoutingSource] is the one place that touches the real repositories;
 * [RoutingViewModel] goes through this interface instead, so
 * [RoutingViewModelTest] can exercise it against a plain JVM fake.
 */
internal interface RoutingSource {
    /** Every stored rule set. See [RoutingRepository.observeAll]. */
    val ruleSets: Flow<List<RoutingRuleSet>>

    /**
     * The currently active rule set, or `null` when routing is off. See
     * [SettingsRepository.activeRoutingRuleSetId].
     */
    val activeRuleSetId: Flow<Long?>

    /**
     * The `.dat` filenames actually on disk. The activation gate's other
     * input, alongside a rule set's own
     * [art.yniyniyni.subspace.core.model.requiredGeoFiles] (spec §4.3).
     */
    val installedGeoFiles: Flow<Set<String>>

    /**
     * Filenames whose most recent install attempt failed — the "Last update
     * failed" marker [RoutingListScreen] shows beside, but never in place of,
     * [installedGeoFiles]'s "needs geo files" marker: a missing file blocks
     * activation, a failed refresh of an already-installed file does not.
     *
     * Defaults to an always-empty flow so [RoutingViewModelTest]'s
     * `FakeSource` — which predates this property and has no reason to
     * simulate a failed download — keeps compiling and passing unmodified;
     * only [BoundRoutingSource] overrides it with a real answer.
     */
    val failedGeoFiles: Flow<Set<String>> get() = flowOf(emptySet())

    /** Sets the active rule set, or turns routing off when [id] is `null`. */
    suspend fun setActive(id: Long?)

    /** Deletes a rule set. A no-op if it no longer exists. */
    suspend fun delete(id: Long)

    /**
     * The rule set with [id], or `null` when it has no matching row. See
     * [RoutingRepository.ruleSet]. Added for [RuleSetEditorViewModel], which
     * loads one rule set's working copy rather than the whole list
     * [RoutingViewModel] combines.
     *
     * Defaults to always-`null` for the same reason [failedGeoFiles] does: so
     * [RoutingViewModelTest]'s `FakeSource` — which predates this member and
     * never reads a single rule set — keeps compiling and passing unmodified;
     * only [BoundRoutingSource] overrides it with a real answer.
     */
    suspend fun ruleSet(id: Long): RoutingRuleSet? = null

    /**
     * The rule set already named [name], or `null`. See
     * [RoutingRepository.ruleSetNamed] — the editor's save-time collision
     * check (fix round 1, Finding 8).
     *
     * Defaults to always-`null` for the same reason [ruleSet] documents. A
     * fake that wants to exercise the collision path must override this.
     */
    suspend fun ruleSetNamed(name: String): RoutingRuleSet? = null

    /**
     * Inserts or updates [set], returning its row id. See
     * [RoutingRepository.upsert] — **throws** [IllegalArgumentException] if any
     * entry fails [art.yniyniyni.subspace.core.model.RoutingEntries.problemWith].
     * [RuleSetEditorViewModel] must never let an invalid entry reach this call.
     *
     * No default: unlike a read, a fake that silently "succeeds" a write it
     * never performed is a defect that produces no failing assertion — a test
     * asserting on [RuleSetEditorViewModel.save] would pass while proving
     * nothing actually got written. A fake that needs this must say so.
     */
    suspend fun upsert(set: RoutingRuleSet): Long

    /**
     * The parsed `<name>.json` sidecar for [field]'s built-in geo database
     * (`geosite.dat` for [BucketField.SITES], `geoip.dat` for
     * [BucketField.IPS]) — see [GeoCategories]. Drives the rule set editor's
     * "browse categories" affordance; empty means that affordance stays
     * disabled, whether because the file does not exist yet or does not parse.
     *
     * A suspend function rather than a raw directory path so the filesystem
     * read stays **behind this source**, not in [RuleSetEditorViewModel] —
     * the same reason [GeoAssetRepository.installedFileNames] does its own
     * `File.isFile` calls rather than handing `geoDirectory()` to
     * `RoutingViewModel` and letting it touch the filesystem directly (§3/§4:
     * the module that owns a path does the I/O against it). [BoundRoutingSource]
     * wraps this in [Dispatchers.IO], the same treatment
     * [GeoAssetRepository.installedFileNames] gives its own reads. (A plain
     * `withContext(Dispatchers.IO)` inside the ViewModel would also have been
     * fine under a test dispatcher — that is a solved problem, not the reason
     * for this seam.)
     *
     * Defaults to an empty list for the same keep-`FakeSource`-compiling reason
     * [ruleSet] documents.
     */
    suspend fun categoriesFor(field: BucketField): List<GeoCategory> = emptyList()
}

@Singleton
internal class BoundRoutingSource
@Inject
constructor(
    private val routingRepository: RoutingRepository,
    private val settingsRepository: SettingsRepository,
    private val geoAssetRepository: GeoAssetRepository,
) : RoutingSource {
    override val ruleSets: Flow<List<RoutingRuleSet>> = routingRepository.observeAll()

    override val activeRuleSetId: Flow<Long?> = settingsRepository.activeRoutingRuleSetId

    /**
     * The activation gate's input, and it must be **the same question the
     * service asks**.
     *
     * `observeAll()` is only the trigger here, not the answer. Deriving the set
     * from the rows directly — `installedAt != null` — asks the database, while
     * `RoutingResolver` in `:service` asks
     * [GeoAssetRepository.installedFileNames], which additionally requires
     * `File(root, name).isFile`. Those disagree exactly when a row says
     * installed and the file is gone: a storage manager sweep, a partial wipe, a
     * rollback that could not restore. In that state a database-only gate shows
     * no marker, enables the selector, permits activation — and the next connect
     * hard-fails with `GeoDataMissing`, which is the outcome this gate exists to
     * prevent.
     *
     * So: re-read through the repository on every emission. The cost is a
     * handful of `File.isFile` calls, already on IO inside the repository, and
     * what it buys is that the screen and the service cannot drift apart.
     */
    override val installedGeoFiles: Flow<Set<String>> =
        geoAssetRepository.observeAll().map { geoAssetRepository.installedFileNames() }

    override val failedGeoFiles: Flow<Set<String>> =
        geoAssetRepository.observeAll().map { assets ->
            assets.filter { it.lastFailure != null }.map { it.fileName }.toSet()
        }

    override suspend fun setActive(id: Long?) = settingsRepository.setActiveRoutingRuleSetId(id)

    override suspend fun delete(id: Long) = routingRepository.delete(id)

    override suspend fun ruleSet(id: Long): RoutingRuleSet? = routingRepository.ruleSet(id)

    override suspend fun ruleSetNamed(name: String): RoutingRuleSet? = routingRepository.ruleSetNamed(name)

    override suspend fun upsert(set: RoutingRuleSet): Long = routingRepository.upsert(set)

    override suspend fun categoriesFor(field: BucketField): List<GeoCategory> =
        withContext(Dispatchers.IO) {
            GeoCategories.read(File(geoAssetRepository.geoDirectory(), categorySidecarFileName(field)))
        }
}
