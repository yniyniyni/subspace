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
import art.yniyniyni.subspace.core.model.RoutingSourceKind
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetFailure
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import art.yniyniyni.subspace.core.parser.routing.RoutingVerb
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class RoutingProfileImporterTest {
    private lateinit var database: SubspaceDatabase
    private lateinit var root: File
    private lateinit var repository: RoutingRepository
    private lateinit var settings: SettingsRepository
    private lateinit var assets: RuleSetAssets
    private lateinit var geoAssets: GeoAssetRepository
    private lateinit var importer: RoutingProfileImporter

    private val downloads = mutableListOf<String>()
    private val downloadTargets = mutableListOf<File>()
    private var downloadFails = false
    private var downloadDelayMillis = 0L

    private val validator =
        object : GeoDataValidator {
            override suspend fun validate(
                datDir: File,
                name: String,
                kind: GeoDataKind,
            ): GeoValidation {
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

    private val downloader =
        GeoDownloader { url, target ->
            downloads += url
            downloadTargets += target
            delay(downloadDelayMillis)
            if (downloadFails) throw IOException("injected download failure")
            target.writeText("valid:$url")
            target.length() to "test-digest"
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
                download = downloader::download,
                clock = { TEST_NOW_MILLIS },
            )
        importer =
            RoutingProfileImporter(
                repository = repository,
                assets = assets,
                geoAssets = geoAssets,
                settings = settings,
                validator = validator,
                downloader = downloader,
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
    fun aDownloadThatOverrunsTheTimeoutIsAbandoned() = runTest {
        downloadDelayMillis = GEO_DOWNLOAD_TIMEOUT_MILLIS + 1

        val outcome = importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

        outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.TimedOut
        repository.observeAllStored().first().single().assetState shouldBe RuleSetAssetState.Failed
        assets.setsRoot().listFiles().orEmpty().shouldBeEmpty()
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
            GeoDownloader { url, target ->
                downloads += url
                target.writeText("corrupt-download")
                target.length() to "test-digest"
            }
        importer =
            RoutingProfileImporter(repository, assets, geoAssets, settings, validator, rejectingDownloader)

        val outcome = importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

        outcome.shouldBeInstanceOf<ImportOutcome.Failed>().failure shouldBe RuleSetAssetFailure.Rejected
        val stored = repository.observeAllStored().first().single()
        stored.assetGeneration shouldBe 0L
        stored.assetState shouldBe RuleSetAssetState.Failed
        assets.setsRoot().listFiles().orEmpty().shouldBeEmpty()
    }

    @Test
    fun aFailedFirstInstallCanBeRetried() = runTest {
        downloadFails = true
        importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)
            .shouldBeInstanceOf<ImportOutcome.Failed>()
        downloadFails = false
        downloads.clear()

        val retry = importer.apply(sampleProfile(), RoutingVerb.OnAdd, RoutingSourceKind.Deeplink, null)

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

    private companion object {
        const val TEST_NOW_MILLIS = 1_800_000_000_000L
    }
}
