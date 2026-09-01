// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import space.getsub.core.data.GeoAssetRepository
import space.getsub.core.data.GeoDownloadProgress
import space.getsub.core.data.GeoDownloadProgressRegistry
import space.getsub.core.data.PendingRoutingConversion
import space.getsub.core.data.PendingRoutingImport
import space.getsub.core.data.ProfileRepository
import space.getsub.core.data.RoutingProfileImporter
import space.getsub.core.data.RoutingRepository
import space.getsub.core.data.RuleSetAssets
import space.getsub.core.data.SettingsRepository
import space.getsub.core.data.StoredRuleSet
import space.getsub.core.data.SubscriptionRepository
import space.getsub.core.data.isDirectiveEnabled
import space.getsub.core.model.BucketField
import space.getsub.core.model.RoutingEntries
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.parser.routing.ImportResult
import space.getsub.core.parser.routing.RoutingConversion
import space.getsub.core.parser.routing.RoutingProfileImport
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
 * [space.getsub.feature.settings.SettingsSource]'s shape: all three
 * repositories have `internal` constructors scoped to `:core:data` (§3), so
 * this module cannot build real instances of them to test against.
 * [BoundRoutingSource] is the one place that touches the real repositories;
 * [RoutingViewModel] goes through this interface instead, so
 * [RoutingViewModelTest] can exercise it against a plain JVM fake.
 */
/** The `routing` directive key — the header transport for a routing deeplink. */
private const val ROUTING_DIRECTIVE_KEY = "routing"

/** The `routing-enable` directive key — the directive form of `/off` (spec §5.6). */
private const val ROUTING_ENABLE_DIRECTIVE_KEY = "routing-enable"

/** What `routing-enable: false` means, expressed as the link the parser already reads. */
private const val DISABLE_ROUTING_LINK = "happ://routing/off"

/**
 * One routing import waiting for the user's review, and where it came from.
 *
 * The channel is carried rather than inferred because it becomes the row's
 * provenance badge — the only place the user ever learns their provider, and
 * not they, installed a rule set.
 */
internal sealed interface RoutingImportOffer {
    /** The link text to hand to the review sheet. */
    val text: String

    /** An `ACTION_VIEW` intent the user tapped. */
    data class Deeplink(
        override val text: String,
    ) : RoutingImportOffer

    /** A `routing` or `routing-enable` directive a subscription sync delivered. */
    data class Provider(
        override val text: String,
        val subscriptionId: Long,
    ) : RoutingImportOffer
}

/** The four inputs [BoundRoutingSource.pendingOffer] combines, named so its `map` can destructure them. */
private data class PendingInputs(
    val deeplink: String?,
    val presented: Set<String>,
    val routingValues: Map<Long, String>,
    val enableValues: Map<Long, String>,
)

// TooManyFunctions: this interface is the module's read/write surface onto RoutingRepository,
// SettingsRepository and GeoAssetRepository together (see the class KDoc a few lines below),
// and its width tracks that surface, not a design that split poorly. Task 15's
// pendingConversion/consumePendingConversion pair pushed it past the default threshold; splitting
// it would only move members into a second interface RoutingViewModel would still depend on both
// halves of.
@Suppress("TooManyFunctions")
internal interface RoutingSource {
    /**
     * Every stored rule set, with its import provenance and asset state. See
     * [RoutingRepository.observeAllStored].
     *
     * Deliberately the `Stored` shape rather than plain [RoutingRuleSet]: an
     * imported profile and a hand-made set share this list (spec §9), and
     * provenance is what tells them apart. A second flow carrying only the
     * provenance would let the two arrive in different frames, rendering a row
     * as editable for one composition after it became read-only.
     */
    val ruleSets: Flow<List<StoredRuleSet>>

    /**
     * The currently active rule set, or `null` when routing is off. See
     * [SettingsRepository.activeRoutingRuleSetId].
     */
    val activeRuleSetId: Flow<Long?>

    /**
     * The `.dat` filenames actually on disk. The activation gate's other
     * input, alongside a rule set's own
     * [space.getsub.core.model.requiredGeoFiles] (spec §4.3).
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

    /**
     * Subscription id to the name of the group it owns — the `From "NameVPN"`
     * badge on a provider-delivered profile.
     *
     * The join lives here rather than in [RoutingRepository] on purpose: a
     * routing-shaped query joining subscriptions and groups would grow
     * `:core:data` a table relationship that exists for one label on one screen.
     * A subscription whose group has no name is simply absent from the map, and
     * the row renders no name rather than `From "null"`.
     *
     * Defaults to empty for the same keep-fakes-compiling reason [ruleSet]
     * documents.
     */
    val subscriptionNames: Flow<Map<Long, String>> get() = flowOf(emptyMap())

    /**
     * Rule set id to the byte counts of the generation it is downloading right
     * now. See [GeoDownloadProgressRegistry].
     *
     * Absent means nothing is in flight. This is process state, not stored
     * state: what survives a restart is the row's own asset state in Room.
     */
    val downloadProgress: Flow<Map<Long, GeoDownloadProgress>> get() = flowOf(emptyMap())

    /**
     * The next routing import awaiting the user's review, or null.
     *
     * One flow for all four channels that do not start on this screen: an
     * `ACTION_VIEW` deeplink, and the two provider directives (`routing` and
     * `routing-enable`) delivered by a subscription sync. Clipboard and QR
     * start here and go straight to the sheet, so they never travel this way.
     *
     * A deeplink wins when both are waiting: the user tapped it a second ago,
     * while a provider directive has been sitting in the database since
     * whenever the last sync ran.
     *
     * Reaches the screen through this source rather than a navigation
     * argument: the link is config material and the back stack is persisted
     * (see [PendingRoutingImport]).
     */
    val pendingOffer: Flow<RoutingImportOffer?> get() = flowOf(null)

    /**
     * A config's `routing` block converted (Task 13) into a rule set by the editor's "Use this
     * config's routing rules" action (Task 15), waiting for this screen's review sheet to take
     * it.
     *
     * Reaches here through [PendingRoutingConversion] rather than a navigation argument, for the
     * same reason [pendingOffer] does — see that holder's own KDoc. Unlike [pendingOffer], this
     * has only one channel and nothing persisted to reconcile: the user just tapped a button on
     * this same run of the app, so there is no "already presented" set to consult.
     *
     * Defaults to an always-null flow for the same keep-`FakeSource`-compiling reason [ruleSet]
     * documents.
     */
    val pendingConversion: Flow<RoutingConversion?> get() = flowOf(null)

    /**
     * Clears [conversion] once the review sheet has taken it. No-op by default; see
     * [pendingConversion].
     */
    fun consumePendingConversion(conversion: RoutingConversion) = Unit

    /**
     * The geo filenames present in [set]'s **own** resolved asset directory.
     *
     * Not the shared root for every row: a profile that owns a generation reads
     * from `geo/sets/<id>/<generation>`, and asking the shared catalogue about
     * it let the list enable a profile whose generation was missing (because a
     * same-named shared file existed) and block a valid one (because the shared
     * root did not have the name). The activation gate must ask exactly what
     * `RoutingResolver` asks.
     *
     * Defaults to empty for the same keep-fakes-compiling reason [ruleSet]
     * documents.
     */
    suspend fun installedGeoFilesFor(set: StoredRuleSet): Set<String> = emptySet()

    /** Sets the active rule set, or turns routing off when [id] is `null`. */
    suspend fun setActive(id: Long?)

    /**
     * Clears [offer] once the review sheet has taken it.
     *
     * A no-op for a provider directive: the directive stays in the database
     * (the provider still sends it), and what stops it being offered again is
     * the fingerprint gate, not a consumed flag. Only the in-memory deeplink
     * has anything to clear.
     */
    fun consumePendingOffer(offer: RoutingImportOffer) = Unit

    /**
     * Stops the generation [id] is materialising. A no-op when nothing is.
     *
     * Not `suspend`: it cancels a job rather than awaiting one, and making it
     * suspend would suggest the caller can wait for the cancellation to settle.
     * The row's bar disappears when the importer unwinds, which the flow
     * reports on its own.
     */
    fun cancelDownload(id: Long) = Unit

    /** Deletes a rule set. A no-op if it no longer exists. */
    suspend fun delete(id: Long)

    /**
     * Copies [id] into an editable rule set named [name], carrying its assets.
     *
     * Goes through the importer rather than a plain `upsert` of the rules: a
     * copy of a generation-owning profile must own copied files, or it silently
     * changes which data its `geosite:`/`geoip:` rules resolve against.
     *
     * Defaults to null for the same keep-fakes-compiling reason [ruleSet]
     * documents.
     */
    suspend fun duplicate(
        id: Long,
        name: String,
    ): Long? = null

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
     * entry fails [space.getsub.core.model.RoutingEntries.problemWith].
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
// LongParameterList: each repository is a distinct boundary this source translates.
// Bundling them into a holder would hide which of them any given member actually
// reads — the same reasoning RoutingProfileImporter's own suppression records.
// TooManyFunctions: this is the module's single translation boundary onto
// :core:data, so its size tracks the repository surface the feature reads.
// Splitting it would only move members without reducing the boundary.
@Suppress("LongParameterList", "TooManyFunctions")
internal class BoundRoutingSource
@Inject
constructor(
    private val routingRepository: RoutingRepository,
    private val settingsRepository: SettingsRepository,
    private val geoAssetRepository: GeoAssetRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val profileRepository: ProfileRepository,
    private val progressRegistry: GeoDownloadProgressRegistry,
    private val importer: RoutingProfileImporter,
    private val pendingRoutingImport: PendingRoutingImport,
    private val pendingRoutingConversion: PendingRoutingConversion,
    private val assets: RuleSetAssets,
) : RoutingSource {
    override val ruleSets: Flow<List<StoredRuleSet>> = routingRepository.observeAllStored()

    override val subscriptionNames: Flow<Map<Long, String>> =
        combine(
            subscriptionRepository.observeSubscriptions(),
            profileRepository.observeGroups(),
        ) { subscriptions, groups ->
            val namesByGroup = groups.associate { it.id to it.name }
            subscriptions.mapNotNull { subscription ->
                namesByGroup[subscription.groupId]?.let { subscription.id to it }
            }.toMap()
        }

    override val downloadProgress: Flow<Map<Long, GeoDownloadProgress>> = progressRegistry.progress

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

    override fun cancelDownload(id: Long) = progressRegistry.cancel(id)

    override suspend fun installedGeoFilesFor(set: StoredRuleSet): Set<String> =
        assets.installedFileNames(
            assets.resolveAssetDir(set.ruleSet.id, set.assetGeneration, set.usesOwnGeneration),
        )

    /**
     * A deeplink if one is waiting, otherwise the first provider directive that
     * would actually change something.
     *
     * The `New`/`Changed` filter is rule 2 of this milestone made structural:
     * a subscription re-delivers its `routing` header on every sync, hourly,
     * and a sheet the user sees hourly is a sheet they stop reading.
     * [RoutingRepository.decideFor] answers with the same fingerprint gate the
     * importer applies, so "would this change anything" has exactly one
     * definition.
     *
     * `routing-enable: false` becomes a synthetic `/off` link rather than a
     * separate path: it *is* the directive form of `/off` (spec §5.6), the
     * parser already reads that verb, and the sheet already renders it. A
     * `true` value is not an offer at all — it asks for nothing to change.
     */
    override val pendingOffer: Flow<RoutingImportOffer?> =
        combine(
            pendingRoutingImport.link,
            pendingRoutingImport.presentedTexts,
            subscriptionRepository.observeDirectiveValues(ROUTING_DIRECTIVE_KEY),
            subscriptionRepository.observeDirectiveValues(ROUTING_ENABLE_DIRECTIVE_KEY),
        ) { deeplink, presented, routingValues, enableValues ->
            PendingInputs(deeplink, presented, routingValues, enableValues)
        }.map { inputs ->
            inputs.deeplink?.let { return@map RoutingImportOffer.Deeplink(it) }
            val unpresented = inputs.routingValues.filterValues { it !in inputs.presented }
            val disableRequests =
                if (DISABLE_ROUTING_LINK in inputs.presented) emptyMap() else inputs.enableValues
            firstUnappliedProfile(unpresented) ?: firstDisableRequest(disableRequests)
            // Parsing runs here, not on the collector's thread: a routing
            // payload is up to MAX_PROFILE_BYTES of base64 and this flow is
            // collected by the routing screen on Main.
        }.flowOn(Dispatchers.Default)

    /** The first provider `routing` value whose profile is not already stored as sent. */
    private suspend fun firstUnappliedProfile(values: Map<Long, String>): RoutingImportOffer? =
        values.entries.firstNotNullOfOrNull { (subscriptionId, value) ->
            val parsed = RoutingProfileImport.parse(value)
            val changes =
                when (parsed) {
                    is ImportResult.Imported ->
                        routingRepository.decideFor(parsed.profile) in
                            setOf(RoutingRepository.UpdateDecision.New, RoutingRepository.UpdateDecision.Changed)
                    // A provider that sends /off in the `routing` key means it,
                    // and there is no fingerprint to compare — so offer it only
                    // while routing is actually on.
                    ImportResult.DisableRouting -> settingsRepository.activeRoutingRuleSetId.first() != null
                    // §5.6: the key is logged elsewhere on rejection; the value
                    // never is, and a malformed one is simply not offered —
                    // raising a sheet that only says "this is broken" for
                    // something the user never asked for is noise.
                    is ImportResult.Invalid -> false
                }
            if (changes) RoutingImportOffer.Provider(value, subscriptionId) else null
        }

    /** `routing-enable: false`, as the `/off` link it means, and only while routing is on. */
    private suspend fun firstDisableRequest(values: Map<Long, String>): RoutingImportOffer? {
        // Nothing to disable is not an offer: a sheet asking to turn off what
        // is already off would be a confirmation with no consequence.
        if (settingsRepository.activeRoutingRuleSetId.first() == null) return null
        return values.entries
            .firstOrNull { (_, value) -> !isDirectiveEnabled(value) }
            ?.let { (subscriptionId, _) -> RoutingImportOffer.Provider(DISABLE_ROUTING_LINK, subscriptionId) }
    }

    override fun consumePendingOffer(offer: RoutingImportOffer) {
        // Both kinds are marked presented; only a deeplink also has in-memory
        // state to clear. A provider directive stays in the database, so
        // "already shown" is the only thing that stops it re-raising.
        pendingRoutingImport.markPresented(offer.text)
        if (offer is RoutingImportOffer.Deeplink) pendingRoutingImport.consume(offer.text)
    }

    override val pendingConversion: Flow<RoutingConversion?> = pendingRoutingConversion.conversion

    override fun consumePendingConversion(conversion: RoutingConversion) =
        pendingRoutingConversion.consume(conversion)

    /**
     * Deletes through [RoutingProfileImporter], not [RoutingRepository].
     *
     * An imported profile owns a generation tree on disk; deleting only its row
     * would orphan every file under it, and nothing else ever sweeps a set that
     * no longer exists.
     */
    override suspend fun delete(id: Long) = importer.delete(id)

    override suspend fun duplicate(
        id: Long,
        name: String,
    ): Long? = importer.duplicate(id, name)

    override suspend fun ruleSet(id: Long): RoutingRuleSet? = routingRepository.ruleSet(id)

    override suspend fun ruleSetNamed(name: String): RoutingRuleSet? = routingRepository.ruleSetNamed(name)

    override suspend fun upsert(set: RoutingRuleSet): Long = routingRepository.upsert(set)

    override suspend fun categoriesFor(field: BucketField): List<GeoCategory> =
        withContext(Dispatchers.IO) {
            GeoCategories.read(File(geoAssetRepository.geoDirectory(), categorySidecarFileName(field)))
        }
}
