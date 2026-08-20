// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.model.GeoValidation
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingProfile
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import art.yniyniyni.subspace.core.parser.routing.RoutingVerb
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@Suppress("LargeClass") // One real Room/filesystem fixture exercises the lifecycle as a single integration boundary.
class RoutingProfileImporterTest {
    private lateinit var database: SubspaceDatabase
    private lateinit var root: File
    private lateinit var repository: RoutingRepository
    private lateinit var settings: SettingsRepository
    private lateinit var assets: RuleSetAssets
    private lateinit var geoAssets: GeoAssetRepository
    private lateinit var importer: RoutingProfileImporter
    private lateinit var deletion: RoutingProfileDeletion

    private val downloads = CopyOnWriteArrayList<String>()
    private val downloadTargets = CopyOnWriteArrayList<File>()
    private var downloadFails = false
    private var downloadDelayMillis = 0L
    private var beforeDownload: suspend (String, File) -> Unit = { _, _ -> }

    /** What the fake downloader emits to its progress callback before finishing. */
    private var reportProgress: (onProgress: (Long, Long?) -> Unit) -> Unit = { }
    private var validationDelayMillis = 0L
    private var validationCalls = 0

    private val validator =
        object : GeoDataValidator {
            override suspend fun validate(
                datDir: File,
                name: String,
                kind: GeoDataKind,
            ): GeoValidation {
                validationCalls += 1
                delay(validationDelayMillis)
                val file = File(datDir, "$name.dat")
                return when {
                    !file.isFile -> GeoValidation.Unreadable
                    file.readText().startsWith("corrupt") -> GeoValidation.NotGeoData
                    else -> {
                        File(datDir, "$name.json").writeText("{\"codes\":[]}")
                        GeoValidation.Valid
                    }
                }
            }
        }

    private val progress = GeoDownloadProgressRegistry()

    private val downloader =
        GeoDownloader { url, target, onProgress ->
            downloads += url
            downloadTargets += target
            beforeDownload(url, target)
            reportProgress(onProgress)
            delay(downloadDelayMillis)
            if (downloadFails) throw IOException("injected download failure")
            target.writeText("valid:$url")
            target.length() to target.sha256()
        }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database =
            Room
                .inMemoryDatabaseBuilder(context, SubspaceDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "routing-importer-${System.nanoTime()}").apply { mkdirs() }
        repository = RoutingRepository(database.routingRuleSetDao())
        settings = SettingsRepository(database.settingDao()) { "test-hwid" }
        assets = RuleSetAssets(root)
        geoAssets =
            GeoAssetRepository(
                dao = database.geoAssetDao(),
                validator = validator,
                root = root,
                download = { url, target -> downloader.download(url, target) { _, _ -> } },
                clock = { TEST_NOW_MILLIS },
            )
        deletion =
            RoutingProfileDeletion(
                database,
                repository,
                assets,
                settings,
                ProfileRepository(database.profileDao()),
            )
        importer =
            RoutingProfileImporter(
                database = database,
                repository = repository,
                assets = assets,
                geoAssets = geoAssets,
                settings = settings,
                validator = validator,
                downloader = downloader,
                deletion = deletion,
                progress = progress,
            )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun anUnchangedProfileTouchesNothing() = runTest {
        val profile = sampleProfile()
        importer.apply(profile, RoutingVerb.Add, RoutingSourceKind.Header, null)
        downloads.clear()
        downloadTargets.clear()

        importer.apply(profile, RoutingVerb.Add, RoutingSourceKind.Header, null) shouldBe
            ImportOutcome.Unchanged

        downloads.shouldBeEmpty()
        downloadTargets.shouldBeEmpty()
        repository.observeAllStored().first().single().assetGeneration shouldBe 1L
    }

    @Test
    fun anUnchangedFailedProfileIsStillAnUnconditionalNoOp() = runTest {
        val profile = geoIpOnlyProfile()
        downloadFails = true
        val failed =
            importer
                .apply(profile, RoutingVerb.OnAdd, RoutingSourceKind.Header, null)
                .shouldBeInstanceOf<ImportOutcome.Failed>()
        val before = repository.stored(failed.id).shouldNotBeNull()
        val treeBefore = assets.setsRoot().walkTopDown().map { it.relativeTo(root).path }.toList()
        downloads.clear()
        downloadTargets.clear()
        validationCalls = 0
        downloadFails = false

        importer.apply(profile, RoutingVerb.OnAdd, RoutingSourceKind.Header, null) shouldBe
            ImportOutcome.Unchanged

        repository.stored(failed.id) shouldBe before
        assets.setsRoot().walkTopDown().map { it.relativeTo(root).path }.toList() shouldBe treeBefore
        downloads.shouldBeEmpty()
        downloadTargets.shouldBeEmpty()
        validationCalls shouldBe 0
        settings.activeRoutingRuleSetId.first() shouldBe null
    }

    @Test
    fun importerInstancesSerializeTheWholeLifecycleForOneProfileName() {
        runBlocking {
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondEntered = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            beforeDownload = { _, _ ->
                when (calls.incrementAndGet()) {
                    1 -> {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                    2 -> {
                        secondEntered.complete(Unit)
                        releaseSecond.await()
                    }
                }
            }
            val otherImporter = newImporter()
            val older = geoIpOnlyProfile()
            val newer =
                older.copy(
                    lastUpdated = older.lastUpdated.shouldNotBeNull() + 60,
                    buckets = mapOf(
                        RouteOutcome.DIRECT to
                            RuleBucket(
                                sites = listOf("domain:newer.example"),
                                ips = listOf("geoip:private"),
                            ),
                    ),
                )

            val olderResult = async { importer.apply(older, RoutingVerb.Add, RoutingSourceKind.Header, null) }
            firstEntered.await()
            val newerResult = async {
                otherImporter.apply(newer, RoutingVerb.Add, RoutingSourceKind.Header, null)
            }

            withTimeoutOrNull(CONCURRENCY_PROBE_MILLIS) { secondEntered.await() } shouldBe null
            releaseFirst.complete(Unit)
            olderResult.await().shouldBeInstanceOf<ImportOutcome.Activated>()
            withTimeout(CONCURRENCY_PROBE_MILLIS) { secondEntered.await() }
            releaseSecond.complete(Unit)
            newerResult.await().shouldBeInstanceOf<ImportOutcome.Stored>()

            val stored = repository.observeAllStored().first().single()
            stored.lastUpdated shouldBe newer.lastUpdated
            stored.ruleSet.bucket(RouteOutcome.DIRECT) shouldBe newer.bucket(RouteOutcome.DIRECT)
            stored.assetGeneration shouldBe 2L
            File(assets.generationDir(stored.ruleSet.id, 2), "geoip.dat").isFile shouldBe true
            assets.generationDir(stored.ruleSet.id, 1).exists() shouldBe false
        }
    }

    @Test
    fun cancellingAWaitingLifecycleDoesNotBlockTheNextImporter() {
        runBlocking {
            val ownerEntered = CompletableDeferred<Unit>()
            val releaseOwner = CompletableDeferred<Unit>()
            val owner =
                launch {
                    RoutingProfileProcessCoordinator.withImport("Cancelled waiter", null) {
                        ownerEntered.complete(Unit)
                        releaseOwner.await()
                    }
                }
            ownerEntered.await()
            val cancelledWaiter =
                launch {
                    RoutingProfileProcessCoordinator.withImport("Cancelled waiter", null) {
                        error("cancelled waiter entered its lifecycle")
                    }
                }
            delay(WAITER_REGISTRATION_MILLIS)
            cancelledWaiter.cancelAndJoin()
            releaseOwner.complete(Unit)
            owner.join()

            withTimeout(CONCURRENCY_PROBE_MILLIS) {
                RoutingProfileProcessCoordinator.withImport("Cancelled waiter", null) { Unit }
            }
        }
    }

    @Test
    @Suppress("LongMethod") // The before/during/after assertions pin every cancellation checkpoint.
    fun cancellationPreservesThePreviousFailureMarkerAndGeneration() {
        runBlocking {
            val original = geoIpOnlyProfile()
            val id =
                importer
                    .apply(original, RoutingVerb.OnAdd, RoutingSourceKind.Header, null)
                    .shouldBeInstanceOf<ImportOutcome.Activated>()
                    .id
            downloadFails = true
            val failedUpdate =
                original.copy(
                    lastUpdated = original.lastUpdated.shouldNotBeNull() + 60,
                    buckets = mapOf(
                        RouteOutcome.DIRECT to
                            RuleBucket(
                                sites = listOf("domain:failed.example"),
                                ips = listOf("geoip:private"),
                            ),
                    ),
                )
            importer.apply(failedUpdate, RoutingVerb.Add, RoutingSourceKind.Header, null)
                .shouldBeInstanceOf<ImportOutcome.Failed>()
            val marker = repository.stored(id).shouldNotBeNull()
            marker.assetState shouldBe RuleSetAssetState.Failed
            marker.assetFailure shouldBe RuleSetAssetFailure.DownloadFailed

            downloadFails = false
            val entered = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            beforeDownload = { _, _ ->
                entered.complete(Unit)
                neverRelease.await()
            }
            val retry =
                failedUpdate.copy(
                    lastUpdated = failedUpdate.lastUpdated.shouldNotBeNull() + 60,
                    buckets = mapOf(
                        RouteOutcome.DIRECT to
                            RuleBucket(
                                sites = listOf("domain:cancelled.example"),
                                ips = listOf("geoip:private"),
                            ),
                    ),
                )
            val job = launch { importer.apply(retry, RoutingVerb.Add, RoutingSourceKind.Header, null) }
            entered.await()

            repository.stored(id).shouldNotBeNull().let { during ->
                during.assetState shouldBe RuleSetAssetState.Failed
                during.assetFailure shouldBe RuleSetAssetFailure.DownloadFailed
                during.assetGeneration shouldBe 1L
            }
            job.cancelAndJoin()

            repository.stored(id).shouldNotBeNull().let { after ->
                after.assetState shouldBe RuleSetAssetState.Failed
                after.assetFailure shouldBe RuleSetAssetFailure.DownloadFailed
                after.assetGeneration shouldBe 1L
                after.ruleSet shouldBe marker.ruleSet
            }
            File(assets.generationDir(id, 1), "geoip.dat").isFile shouldBe true
            assets.generationDir(id, 2).exists() shouldBe false
        }
    }

    @Test
    @Suppress("LongMethod") // The staged, blocked, cancelled, and live states are all part of this boundary.
    fun cancellationAtThePreCommitBarrierRemovesOnlyTheUnpublishedGeneration() {
        runBlocking {
            val original = geoIpOnlyProfile()
            val id =
                importer
                    .apply(original, RoutingVerb.OnAdd, RoutingSourceKind.Header, null)
                    .shouldBeInstanceOf<ImportOutcome.Activated>()
                    .id
            repository.markAssets(id, RuleSetAssetState.Failed, RuleSetAssetFailure.DownloadFailed)
            val marker = repository.stored(id).shouldNotBeNull()
            val preCommitEntered = CompletableDeferred<Unit>()
            val neverCommit = CompletableDeferred<Unit>()
            val barrierImporter =
                RoutingProfileImporter(
                    database,
                    repository,
                    assets,
                    geoAssets,
                    settings,
                    validator,
                    downloader,
                    deletion,
                    progress,
                    GEO_DOWNLOAD_TIMEOUT_MILLIS,
                ) { committedId: Long, generation: Long ->
                    committedId shouldBe id
                    generation shouldBe 2L
                    preCommitEntered.complete(Unit)
                    neverCommit.await()
                }
            val update =
                original.copy(
                    lastUpdated = original.lastUpdated.shouldNotBeNull() + 60,
                    buckets = mapOf(
                        RouteOutcome.DIRECT to
                            RuleBucket(
                                sites = listOf("domain:precommit.example"),
                                ips = listOf("geoip:private"),
                            ),
                    ),
                )
            val applying = launch(Dispatchers.Default) {
                barrierImporter.apply(update, RoutingVerb.Add, RoutingSourceKind.Header, null)
            }
            preCommitEntered.await()

            assets.verifiedGenerationFile(id, 2, "geoip.dat").shouldNotBeNull()
            repository.stored(id).shouldNotBeNull().assetGeneration shouldBe 1L
            applying.cancelAndJoin()

            val after = repository.stored(id).shouldNotBeNull()
            after.assetGeneration shouldBe 1L
            after.assetState shouldBe marker.assetState
            after.assetFailure shouldBe marker.assetFailure
            after.ruleSet shouldBe marker.ruleSet
            File(assets.generationDir(id, 1), "geoip.dat").isFile shouldBe true
            assets.generationDir(id, 2).exists() shouldBe false
        }
    }

    @Test
    fun cancellationObservedAfterPublicationNeverDeletesTheNewlyLiveGeneration() {
        runBlocking {
            val original = geoIpOnlyProfile()
            val id =
                importer
                    .apply(original, RoutingVerb.OnAdd, RoutingSourceKind.Header, null)
                    .shouldBeInstanceOf<ImportOutcome.Activated>()
                    .id
            val update =
                original.copy(
                    lastUpdated = original.lastUpdated.shouldNotBeNull() + 60,
                    buckets = mapOf(
                        RouteOutcome.DIRECT to
                            RuleBucket(
                                sites = listOf("domain:published-before-cancel.example"),
                                ips = listOf("geoip:private"),
                            ),
                    ),
                )
            val publicationLanded = CompletableDeferred<Unit>()
            val neverReturnFromCommit = CompletableDeferred<Unit>()
            val barrierImporter =
                RoutingProfileImporter(
                    database,
                    repository,
                    assets,
                    geoAssets,
                    settings,
                    validator,
                    downloader,
                    deletion,
                    progress,
                    GEO_DOWNLOAD_TIMEOUT_MILLIS,
                ) { committedId: Long, generation: Long ->
                    repository.commitGeneration(committedId, update, generation)
                    publicationLanded.complete(Unit)
                    neverReturnFromCommit.await()
                }
            val applying = launch(Dispatchers.Default) {
                barrierImporter.apply(update, RoutingVerb.Add, RoutingSourceKind.Header, null)
            }
            publicationLanded.await()

            applying.cancelAndJoin()

            val live = repository.stored(id).shouldNotBeNull()
            live.assetGeneration shouldBe 2L
            live.ruleSet.bucket(RouteOutcome.DIRECT) shouldBe update.bucket(RouteOutcome.DIRECT)
            assets.generationDir(id, 1).exists() shouldBe false
            File(assets.generationDir(id, 2), "geoip.dat").isFile shouldBe true
        }
    }

    @Test
    fun manualSaveWaitsForImportAndPublishesOneConsistentRow() {
        runBlocking {
            val importEntered = CompletableDeferred<Unit>()
            val releaseImport = CompletableDeferred<Unit>()
            beforeDownload = { _, _ ->
                importEntered.complete(Unit)
                releaseImport.await()
            }
            val profile = geoIpOnlyProfile().copy(name = "Manual collision")
            val manual =
                RoutingRuleSet(
                    name = profile.name,
                    buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("domain:manual.example"))),
                    globalProxy = true,
                )

            val importing = async {
                importer.apply(profile, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            }
            importEntered.await()
            val saving = async { repository.upsert(manual) }

            withTimeoutOrNull(CONCURRENCY_PROBE_MILLIS) { saving.await() } shouldBe null
            releaseImport.complete(Unit)
            importing.await().shouldBeInstanceOf<ImportOutcome.Activated>()
            val id = saving.await()

            val stored = repository.stored(id).shouldNotBeNull()
            stored.ruleSet.name shouldBe manual.name
            stored.ruleSet.bucket(RouteOutcome.BLOCK) shouldBe manual.bucket(RouteOutcome.BLOCK)
            stored.ruleSet.bucket(RouteOutcome.DIRECT).isEmpty shouldBe true
            stored.ruleSet.bucket(RouteOutcome.PROXY).isEmpty shouldBe true
            stored.ruleSet.globalProxy shouldBe manual.globalProxy
            stored.sourceKind shouldBe null
            stored.subscriptionId shouldBe null
            stored.lastUpdated shouldBe null
            stored.fingerprint shouldBe null
            stored.geoIpUrl shouldBe null
            stored.assetGeneration shouldBe 0L
            stored.assetState shouldBe RuleSetAssetState.None
        }
    }

    @Test
    fun existingIdRenameLocksItsCurrentAliasAgainstAConcurrentImport() {
        runBlocking {
            val currentName = "A current manual alias"
            val requestedName = "Z requested manual alias"
            val original =
                RoutingRuleSet(
                    name = currentName,
                    buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("domain:manual.example"))),
                )
            val id = repository.upsert(original)
            val renamed = original.copy(id = id, name = requestedName)
            val requestedHeld = CompletableDeferred<Unit>()
            val releaseRequested = CompletableDeferred<Unit>()
            val requestedHolder =
                launch {
                    RoutingProfileProcessCoordinator.withProfiles(listOf(requestedName)) {
                        requestedHeld.complete(Unit)
                        releaseRequested.await()
                    }
                }
            requestedHeld.await()
            val renaming = async { repository.upsert(renamed) }
            delay(WAITER_REGISTRATION_MILLIS)
            val importing = async {
                importer.apply(
                    geoIpOnlyProfile().copy(name = currentName),
                    RoutingVerb.Add,
                    RoutingSourceKind.Deeplink,
                    null,
                )
            }

            withTimeoutOrNull(CONCURRENCY_PROBE_MILLIS) { importing.await() } shouldBe null
            releaseRequested.complete(Unit)
            renaming.await() shouldBe id
            importing.await().shouldBeInstanceOf<ImportOutcome.Activated>()
            requestedHolder.join()

            val stored = repository.observeAllStored().first()
            stored.map { it.ruleSet.name }.toSet() shouldBe setOf(currentName, requestedName)
            stored.first { it.ruleSet.name == requestedName }.let { manual ->
                manual.ruleSet.bucket(RouteOutcome.BLOCK) shouldBe renamed.bucket(RouteOutcome.BLOCK)
                manual.assetGeneration shouldBe 0L
                manual.sourceKind shouldBe null
            }
            stored.first { it.ruleSet.name == currentName }.let { imported ->
                imported.assetGeneration shouldBe 1L
                imported.sourceKind shouldBe RoutingSourceKind.Deeplink
                File(assets.generationDir(imported.ruleSet.id, 1), "geoip.dat").isFile shouldBe true
            }
        }
    }

    @Test
    fun aStaleProfileIsRefusedWithoutDownloading() = runTest {
        val profile = sampleProfile()
        importer.apply(profile, RoutingVerb.Add, RoutingSourceKind.Header, null)
        downloads.clear()

        val replayed =
            profile.copy(
                lastUpdated = profile.lastUpdated.shouldNotBeNull() - 60,
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads"))),
            )

        importer.apply(replayed, RoutingVerb.Add, RoutingSourceKind.Header, null) shouldBe
            ImportOutcome.Stale
        downloads.shouldBeEmpty()
        repository.observeAllStored().first().single().ruleSet.bucket(RouteOutcome.BLOCK).sites.shouldBeEmpty()
    }

    @Test
    fun addActivatesOnlyWhenNothingElseIsActive() = runTest {
        val first = importer.apply(sampleProfile(), RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
        first.shouldBeInstanceOf<ImportOutcome.Activated>()

        val second =
            importer.apply(
                sampleProfile().copy(name = "Second"),
                RoutingVerb.Add,
                RoutingSourceKind.Deeplink,
                null,
            )

        second.shouldBeInstanceOf<ImportOutcome.Stored>()
        settings.activeRoutingRuleSetId.first() shouldBe first.id
    }

    @Test
    fun onAddDisplacesTheActiveProfile() = runTest {
        importer.apply(sampleProfile(), RoutingVerb.Add, RoutingSourceKind.Deeplink, null)

        val second =
            importer
                .apply(
                    sampleProfile().copy(name = "Second"),
                    RoutingVerb.OnAdd,
                    RoutingSourceKind.Deeplink,
                    null,
                ).shouldBeInstanceOf<ImportOutcome.Activated>()

        settings.activeRoutingRuleSetId.first() shouldBe second.id
    }

    @Test
    fun concurrentAddsProduceExactlyOneActivatedOutcome() {
        runBlocking {
            val entered = AtomicInteger()
            val bothEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            beforeDownload = { _, _ ->
                if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                release.await()
            }
            val firstImporter = newImporter()
            val secondImporter = newImporter()
            val firstProfile = geoIpOnlyProfile().copy(name = "First concurrent")
            val secondProfile = geoIpOnlyProfile().copy(name = "Second concurrent")

            val first = async {
                firstImporter.apply(firstProfile, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            }
            val second = async {
                secondImporter.apply(secondProfile, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            }
            withTimeout(CONCURRENCY_PROBE_MILLIS) { bothEntered.await() }
            release.complete(Unit)
            val outcomes = listOf(first.await(), second.await())

            outcomes.count { it is ImportOutcome.Activated } shouldBe 1
            outcomes.count { it is ImportOutcome.Stored } shouldBe 1
            val activatedId = outcomes.single { it is ImportOutcome.Activated } as ImportOutcome.Activated
            settings.activeRoutingRuleSetId.first() shouldBe activatedId.id
        }
    }

    @Test
    fun aProfileWithNoGeoReferencesActivatesWithZeroDownloads() = runTest {
        val lanOnly =
            sampleProfile().copy(
                name = "LAN",
                geoIpUrl = null,
                geoSiteUrl = null,
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8"))),
            )

        importer.apply(lanOnly, RoutingVerb.OnAdd, RoutingSourceKind.Clipboard, null)
            .shouldBeInstanceOf<ImportOutcome.Activated>()

        downloads.shouldBeEmpty()
        repository.observeAllStored().first().single().assetGeneration shouldBe 0L
    }

    @Test
    fun aFailedDownloadLeavesTheOldGenerationLiveAndMarksTheSet() = runTest {
        val id =
            importer
                .apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Header, null)
                .shouldBeInstanceOf<ImportOutcome.Activated>()
                .id
        val liveBytes = File(assets.generationDir(id, 1), "geoip.dat").readBytes()

        downloadFails = true
        val next =
            sampleProfile().copy(
                lastUpdated = sampleProfile().lastUpdated.shouldNotBeNull() + 60,
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:ads"))),
            )

        importer.apply(next, RoutingVerb.Add, RoutingSourceKind.Header, null)
            .shouldBeInstanceOf<ImportOutcome.Failed>()

        val stored = repository.stored(id).shouldNotBeNull()
        stored.assetGeneration shouldBe 1L
        stored.assetState shouldBe RuleSetAssetState.Failed
        stored.assetFailure shouldBe RuleSetAssetFailure.DownloadFailed
        stored.ruleSet.bucket(RouteOutcome.BLOCK).sites.shouldBeEmpty()
        File(assets.generationDir(id, 1), "geoip.dat").readBytes().contentEquals(liveBytes) shouldBe true
        assets.generationDir(id, 2).exists() shouldBe false
    }

    @Test
    fun aDownloadThatOverrunsTheTimeoutIsAbandoned() {
        runBlocking {
            downloadDelayMillis = SLOW_VALIDATION_MILLIS

            val outcome =
                shortTimeoutImporter().apply(
                    sampleProfile(),
                    RoutingVerb.OnAdd,
                    RoutingSourceKind.Deeplink,
                    null,
                )

            outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.TimedOut
            repository.observeAllStored().first().single().assetState shouldBe RuleSetAssetState.Failed
            assets.setsRoot().listFiles().orEmpty().shouldBeEmpty()
        }
    }

    @Test
    fun theSingleTimeoutAlsoBoundsLocalCandidateValidation() {
        runBlocking {
            importer.apply(geoIpOnlyProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
                .shouldBeInstanceOf<ImportOutcome.Activated>()
            validationDelayMillis = SLOW_VALIDATION_MILLIS
            downloads.clear()
            val shortTimeoutImporter = shortTimeoutImporter()

            val outcome =
                shortTimeoutImporter.apply(
                    geoIpOnlyProfile().copy(name = "Slow local copy"),
                    RoutingVerb.Add,
                    RoutingSourceKind.Deeplink,
                    null,
                )

            outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.TimedOut
            downloads.shouldBeEmpty()
            val stored = repository.observeAllStored().first().first { it.ruleSet.name == "Slow local copy" }
            stored.assetGeneration shouldBe 0L
            stored.assetState shouldBe RuleSetAssetState.Failed
            File(assets.setsRoot(), stored.ruleSet.id.toString()).exists() shouldBe false
        }
    }

    @Test
    fun synchronousNativeStyleValidationCanOutliveTheDeadlineBeforeTimeoutIsObserved() {
        runBlocking {
            val blockingValidator =
                object : GeoDataValidator {
                    override suspend fun validate(
                        datDir: File,
                        name: String,
                        kind: GeoDataKind,
                    ): GeoValidation {
                        Thread.sleep(SLOW_VALIDATION_MILLIS)
                        return GeoValidation.Valid
                    }
                }
            val blockingImporter =
                RoutingProfileImporter(
                    database,
                    repository,
                    assets,
                    geoAssets,
                    settings,
                    blockingValidator,
                    downloader,
                    deletion,
                    progress,
                    TEST_MATERIALISATION_TIMEOUT_MILLIS,
                )
            lateinit var outcome: ImportOutcome

            val elapsed = measureTimeMillis {
                outcome = blockingImporter.apply(
                    geoIpOnlyProfile().copy(name = "Synchronous native validation"),
                    RoutingVerb.Add,
                    RoutingSourceKind.Deeplink,
                    null,
                )
            }

            outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.TimedOut
            (elapsed >= SLOW_VALIDATION_MILLIS) shouldBe true
        }
    }

    @Test
    fun bytesAlreadyOnDiskAreCopiedNotRefetched() = runTest {
        val first = importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
        first.shouldBeInstanceOf<ImportOutcome.Activated>()
        downloads shouldHaveSize 2
        downloads.clear()

        importer.apply(
            sampleProfile().copy(name = "Sibling"),
            RoutingVerb.Add,
            RoutingSourceKind.Deeplink,
            null,
        ).shouldBeInstanceOf<ImportOutcome.Stored>()

        downloads.shouldBeEmpty()
        val sibling = repository.observeAllStored().first().first { it.ruleSet.name == "Sibling" }
        File(assets.generationDir(sibling.ruleSet.id, 1), "geoip.dat").isFile shouldBe true
        File(assets.generationDir(sibling.ruleSet.id, 1), "geosite.dat").isFile shouldBe true
    }

    @Test
    fun everyDownloadTargetIsInsideTheFreshGeneration() = runTest {
        val outcome =
            importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
                .shouldBeInstanceOf<ImportOutcome.Activated>()
        val generation = assets.generationDir(outcome.id, 1).canonicalFile

        downloadTargets shouldHaveSize 2
        downloadTargets.forEach { target ->
            target.parentFile?.canonicalFile shouldBe generation
        }
    }

    @Test
    fun aCorruptLocalCandidateIsRefetchedInsteadOfPropagated() = runTest {
        val first =
            importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
                .shouldBeInstanceOf<ImportOutcome.Activated>()
        File(assets.generationDir(first.id, 1), "geoip.dat").writeText("corrupt-local-copy")
        downloads.clear()

        val sibling =
            sampleProfile().copy(
                name = "Sibling",
                geoSiteUrl = null,
                buckets = mapOf(
                    RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:private")),
                ),
            )
        importer.apply(sibling, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            .shouldBeInstanceOf<ImportOutcome.Stored>()

        downloads shouldBe listOf(sibling.geoIpUrl)
        val siblingId = repository.observeAllStored().first().first { it.ruleSet.name == "Sibling" }.ruleSet.id
        File(assets.generationDir(siblingId, 1), "geoip.dat").readText().startsWith("valid:") shouldBe true
    }

    @Test
    fun aRejectedDownloadNeverBecomesLive() = runTest {
        val rejectingDownloader =
            GeoDownloader { url, target, _ ->
                downloads += url
                target.writeText("corrupt-download")
                target.length() to "test-digest"
            }
        importer = newImporter(rejectingDownloader)

        val outcome = importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

        outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.Rejected
        val stored = repository.observeAllStored().first().single()
        stored.assetGeneration shouldBe 0L
        stored.assetState shouldBe RuleSetAssetState.Failed
        assets.setsRoot().listFiles().orEmpty().shouldBeEmpty()
    }

    @Test
    fun aFailedFirstInstallCanBeRetriedWithChangedContent() = runTest {
        val failedProfile = sampleProfile()
        downloadFails = true
        importer.apply(failedProfile, RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
            .shouldBeInstanceOf<ImportOutcome.Failed>()
        downloadFails = false
        downloads.clear()
        val retryProfile =
            failedProfile.copy(
                lastUpdated = failedProfile.lastUpdated.shouldNotBeNull() + 60,
                buckets = failedProfile.buckets +
                    (RouteOutcome.BLOCK to RuleBucket(sites = listOf("domain:ads.example"))),
            )

        val retry = importer.apply(retryProfile, RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

        retry.shouldBeInstanceOf<ImportOutcome.Activated>()
        downloads shouldHaveSize 2
        repository.stored(retry.id).shouldNotBeNull().assetState shouldBe RuleSetAssetState.Ready
    }

    @Test
    fun anUnsuppliedExtFileFailsBeforeAnyDownload() = runTest {
        val profile =
            sampleProfile().copy(
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf("ext:custom.dat:local"))),
            )

        val outcome = importer.apply(profile, RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

        outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.Rejected
        downloads.shouldBeEmpty()
        settings.activeRoutingRuleSetId.first() shouldBe null
    }

    @Test
    fun previewIsReadOnlyAndReportsThePendingDownloads() = runTest {
        val profile = sampleProfile()

        val preview = importer.preview(profile)

        preview.decision shouldBe RoutingRepository.UpdateDecision.New
        preview.profile shouldBe profile
        preview.replacesExisting shouldBe false
        preview.willActivate shouldBe true
        preview.geoFiles.map { it.fileName } shouldBe listOf("geoip.dat", "geosite.dat")
        preview.geoFiles.all { !it.alreadyOnDevice && it.approximateBytes == null } shouldBe true
        repository.observeAllStored().first().shouldBeEmpty()
        downloads.shouldBeEmpty()
        assets.setsRoot().exists() shouldBe false
    }

    @Test
    fun previewRecognisesAnExactSharedUrlWithoutDownloading() = runTest {
        geoAssets.install(
            GeoInstallRequest(
                fileName = "geoip.dat",
                sourceUrl = sampleProfile().geoIpUrl.shouldNotBeNull(),
                geoType = GeoDataKind.IP,
            ),
        ) shouldBe GeoInstallResult.Installed
        downloads.clear()

        val preview = importer.preview(sampleProfile().copy(geoSiteUrl = null))

        preview.geoFiles.single().alreadyOnDevice shouldBe true
        preview.geoFiles.single().approximateBytes shouldBe File(root, "geoip.dat").length()
        downloads.shouldBeEmpty()
    }

    @Test
    fun failedSharedSourceReplacementCannotMasqueradeAsTheRequestedUrl() = runTest {
        val sourceA = "https://assets.example/a/geoip.dat"
        val sourceB = "https://assets.example/b/geoip.dat"
        geoAssets.install(
            GeoInstallRequest("geoip.dat", sourceA, GeoDataKind.IP),
        ) shouldBe GeoInstallResult.Installed
        downloadFails = true
        geoAssets.install(
            GeoInstallRequest("geoip.dat", sourceB, GeoDataKind.IP),
        ) shouldBe GeoInstallResult.DownloadFailed
        downloadFails = false
        downloads.clear()
        val profile = geoIpOnlyProfile().copy(name = "Source B", geoIpUrl = sourceB)

        importer.preview(profile).geoFiles.single().alreadyOnDevice shouldBe false
        importer.apply(profile, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            .shouldBeInstanceOf<ImportOutcome.Activated>()

        downloads shouldBe listOf(sourceB)
        File(assets.generationDir(repository.observeAllStored().first().single().ruleSet.id, 1), "geoip.dat")
            .readText() shouldBe "valid:$sourceB"
    }

    @Test
    fun previewRejectsACorruptCandidateWithoutWritingAnything() = runTest {
        geoAssets.install(
            GeoInstallRequest(
                fileName = "geoip.dat",
                sourceUrl = sampleProfile().geoIpUrl.shouldNotBeNull(),
                geoType = GeoDataKind.IP,
            ),
        ) shouldBe GeoInstallResult.Installed
        File(root, "geoip.dat").writeText("corrupt-after-validation")
        downloads.clear()
        validationCalls = 0
        val before = fileTreeSnapshot(root)

        val preview = importer.preview(sampleProfile().copy(geoSiteUrl = null))

        preview.geoFiles.single().alreadyOnDevice shouldBe false
        validationCalls shouldBe 0
        downloads.shouldBeEmpty()
        fileTreeSnapshot(root) shouldBe before
    }

    @Test
    fun previewRecognisesAnotherSetsDigestVerifiedLiveGenerationWithoutWriting() = runTest {
        importer.apply(geoIpOnlyProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
        downloads.clear()
        validationCalls = 0
        val before = fileTreeSnapshot(root)

        val preview = importer.preview(geoIpOnlyProfile().copy(name = "Sibling"))

        preview.geoFiles.single().alreadyOnDevice shouldBe true
        val existing = repository.observeAllStored().first().single()
        preview.geoFiles.single().approximateBytes shouldBe
            File(assets.generationDir(existing.ruleSet.id, 1), "geoip.dat").length()
        validationCalls shouldBe 0
        downloads.shouldBeEmpty()
        fileTreeSnapshot(root) shouldBe before
    }

    @Test
    fun previewRejectsAnotherSetsGenerationWhenBytesNoLongerMatchItsDigest() = runTest {
        val first =
            importer
                .apply(geoIpOnlyProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
                .shouldBeInstanceOf<ImportOutcome.Activated>()
        File(assets.generationDir(first.id, 1), "geoip.dat").writeText("corrupt-after-publication")
        downloads.clear()
        validationCalls = 0
        val before = fileTreeSnapshot(root)

        val preview = importer.preview(geoIpOnlyProfile().copy(name = "Corrupt sibling"))

        preview.geoFiles.single().alreadyOnDevice shouldBe false
        validationCalls shouldBe 0
        downloads.shouldBeEmpty()
        fileTreeSnapshot(root) shouldBe before
    }

    @Test
    fun disablingRoutingKeepsTheStoredSet() = runTest {
        importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

        importer.disableRouting()

        settings.activeRoutingRuleSetId.first() shouldBe null
        repository.observeAllStored().first() shouldHaveSize 1
    }

    @Test
    fun deletingASetRemovesItsGenerationTree() = runTest {
        val id =
            importer
                .apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
                .shouldBeInstanceOf<ImportOutcome.Activated>()
                .id
        assets.generationDir(id, 1).isDirectory shouldBe true

        importer.delete(id)

        File(assets.setsRoot(), id.toString()).exists() shouldBe false
        repository.stored(id) shouldBe null
        settings.activeRoutingRuleSetId.first() shouldBe null
    }

    @Test
    fun deletingASubscriptionRemovesOwnedGenerationTreesAndActiveSelection() = runTest {
        val subscriptions =
            SubscriptionRepository(
                database.subscriptionDao(),
                ProfileRepository(database.profileDao()),
                database,
                deletion,
            )
        val subscription =
            subscriptions.add("https://subscription.example/config", "Provider")
        val id =
            importer
                .apply(
                    geoIpOnlyProfile(),
                    RoutingVerb.OnAdd,
                    RoutingSourceKind.Header,
                    subscription.id,
                ).shouldBeInstanceOf<ImportOutcome.Activated>()
                .id
        assets.generationDir(id, 1).isDirectory shouldBe true

        subscriptions.delete(subscription.id)

        repository.stored(id) shouldBe null
        File(assets.setsRoot(), id.toString()).exists() shouldBe false
        settings.activeRoutingRuleSetId.first() shouldBe null
    }

    @Test
    fun importQueuedBehindSubscriptionDeletionReturnsThePinnedPreRowFailure() {
        runBlocking {
            val subscriptions =
                SubscriptionRepository(
                    database.subscriptionDao(),
                    ProfileRepository(database.profileDao()),
                    database,
                    deletion,
                )
            val subscription = subscriptions.add("https://subscription.example/race", "Race provider")
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            beforeDownload = { url, _ ->
                if (url.contains("first")) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            }
            val firstProfile =
                geoIpOnlyProfile().copy(
                    name = "First subscription profile",
                    geoIpUrl = "https://assets.example/first.dat",
                )
            val queuedProfile =
                geoIpOnlyProfile().copy(
                    name = "Queued after deletion",
                    geoIpUrl = "https://assets.example/queued.dat",
                )
            val first = async {
                importer.apply(firstProfile, RoutingVerb.Add, RoutingSourceKind.Header, subscription.id)
            }
            firstEntered.await()
            val deleting = async { subscriptions.delete(subscription.id) }
            delay(WAITER_REGISTRATION_MILLIS)
            val queued = async {
                importer.apply(queuedProfile, RoutingVerb.Add, RoutingSourceKind.Header, subscription.id)
            }
            delay(WAITER_REGISTRATION_MILLIS)

            releaseFirst.complete(Unit)
            first.await().shouldBeInstanceOf<ImportOutcome.Activated>()
            deleting.await()
            queued.await() shouldBe ImportOutcome.Failed(
                id = 0L,
                failure = RuleSetAssetFailure.Rejected,
            )

            downloads.count { it == queuedProfile.geoIpUrl } shouldBe 0
            repository.observeAllStored().first().shouldBeEmpty()
            assets.setsRoot().listFiles().orEmpty().shouldBeEmpty()
        }
    }

    private fun sampleProfile(): RoutingProfile =
        RoutingProfile(
            name = "Provider routing",
            globalProxy = false,
            buckets = mapOf(
                RouteOutcome.DIRECT to
                    RuleBucket(
                        sites = listOf("geosite:private"),
                        ips = listOf("geoip:private"),
                    ),
            ),
            geoIpUrl = "https://assets.example/geoip.dat",
            geoSiteUrl = "https://assets.example/geosite.dat",
            lastUpdated = 1_800_000_000L,
        )

    // Spec §9: the row renders "Downloading 12 MB / 23 MB". The counts have to
    // reach a reader outside the importer while the download is still running,
    // not after it finishes.
    @Test
    fun aRunningDownloadPublishesItsByteCountUnderTheRuleSetId() {
        runBlocking {
            val seen = CopyOnWriteArrayList<Map<Long, GeoDownloadProgress>>()
            reportProgress = { onProgress -> onProgress(12L, 23L) }
            beforeDownload = { _, _ -> }
            val watcher = launch(Dispatchers.Default) { progress.progress.collect { seen += it } }

            val outcome = importer.apply(geoIpOnlyProfile(), RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            watcher.cancelAndJoin()

            outcome.shouldBeInstanceOf<ImportOutcome.Activated>()
            val id = outcome.id
            seen.mapNotNull { it[id] } shouldContain GeoDownloadProgress("geoip.dat", 12L, 23L)
            // The bar is gone once the generation lands: a stale bar on a
            // finished set would never advance.
            progress.progress.first()[id] shouldBe null
        }
    }

    @Test
    fun cancellingAnInFlightGenerationLeavesThePreviousOneLive() {
        runBlocking {
            val original = geoIpOnlyProfile()
            val first = importer.apply(original, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
            first.shouldBeInstanceOf<ImportOutcome.Activated>()
            val id = first.id
            val liveGeneration = repository.stored(id).shouldNotBeNull().assetGeneration
            val downloadEntered = CompletableDeferred<Unit>()
            beforeDownload = { _, _ ->
                downloadEntered.complete(Unit)
                CompletableDeferred<Unit>().await() // held open until the cancel lands
            }
            val update =
                original.copy(
                    lastUpdated = original.lastUpdated.shouldNotBeNull() + 60,
                    buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:cn"))),
                )
            val running =
                launch(Dispatchers.Default) {
                    importer.apply(update, RoutingVerb.Add, RoutingSourceKind.Deeplink, null)
                }
            downloadEntered.await()

            progress.cancel(id)
            running.join()

            repository.stored(id).shouldNotBeNull().assetGeneration shouldBe liveGeneration
            progress.progress.first()[id] shouldBe null
        }
    }

    private fun geoIpOnlyProfile(): RoutingProfile =
        sampleProfile().copy(
            geoSiteUrl = null,
            buckets = mapOf(
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:private")),
            ),
        )

    private fun shortTimeoutImporter(): RoutingProfileImporter =
        RoutingProfileImporter(
            database,
            repository,
            assets,
            geoAssets,
            settings,
            validator,
            downloader,
            deletion,
            progress,
            TEST_MATERIALISATION_TIMEOUT_MILLIS,
        )

    private fun newImporter(geoDownloader: GeoDownloader = downloader): RoutingProfileImporter =
        RoutingProfileImporter(
            database,
            repository,
            assets,
            geoAssets,
            settings,
            validator,
            geoDownloader,
            deletion,
            progress,
        )

    private fun fileTreeSnapshot(directory: File): List<String> =
        directory
            .walkTopDown()
            .map { file ->
                val path = file.relativeTo(directory).path
                if (file.isFile) "$path:${file.length()}:${file.sha256()}" else "$path/"
            }.toList()

    private fun File.sha256(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TEST_NOW_MILLIS = 1_800_000_000_000L
        const val CONCURRENCY_PROBE_MILLIS = 1_000L
        const val WAITER_REGISTRATION_MILLIS = 100L
        const val TEST_MATERIALISATION_TIMEOUT_MILLIS = 100L
        const val SLOW_VALIDATION_MILLIS = 1_000L
    }
}
