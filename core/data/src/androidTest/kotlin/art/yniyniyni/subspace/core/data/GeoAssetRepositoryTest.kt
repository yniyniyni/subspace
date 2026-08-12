// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.GeoAssetDao
import art.yniyniyni.subspace.core.data.db.GeoAssetEntity
import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.model.GeoValidation
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class GeoAssetRepositoryTest {
    private lateinit var stack: InMemoryGeoStack

    /** Answers whatever the test sets, and records what it was asked. */
    private class FakeValidator(var answer: GeoValidation = GeoValidation.Valid) : GeoDataValidator {
        var exception: Exception? = null
        var writesSidecar: Boolean = true
        var sidecarContents: String = "{\"codes\":[]}"
        var lastDir: File? = null
        var lastName: String? = null
        var lastKind: GeoDataKind? = null
        val stagingDirectories = ConcurrentLinkedQueue<File>()

        override suspend fun validate(
            datDir: File,
            name: String,
            kind: GeoDataKind,
        ): GeoValidation {
            lastDir = datDir
            lastName = name
            lastKind = kind
            stagingDirectories += datDir
            exception?.let { throw it }
            // The real validator writes this; the install must move it too.
            if (answer == GeoValidation.Valid && writesSidecar) {
                File(datDir, "$name.json").writeText(sidecarContents)
            }
            return answer
        }
    }

    private val validator = FakeValidator()

    @Before
    fun setUp() {
        stack =
            InMemoryGeoStack(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                validator = validator,
                // Serves fixed bytes instead of reaching the network.
                download = { _, target ->
                    target.writeText("payload")
                    7L to "digest"
                },
            )
    }

    @After
    fun tearDown() = stack.close()

    private fun request(fileName: String = "geosite.dat") =
        GeoInstallRequest(
            fileName = fileName,
            sourceUrl = "https://example.invalid/dlc.dat",
            geoType = GeoDataKind.DOMAIN,
        )

    @Test
    fun installsIntoTheLiveDirectoryAndRecordsTheRow() = runTest {
        stack.repository.install(request()) shouldBe GeoInstallResult.Installed

        File(stack.repository.geoDirectory(), "geosite.dat").readText() shouldBe "payload"
        val row = stack.repository.observeAll().first().single()
        row.fileName shouldBe "geosite.dat"
        row.sizeBytes shouldBe 7L
        row.installedAt shouldBe stack.now
    }

    // The category list countGeoData writes must land beside the .dat, or the
    // rule editor has nothing to offer.
    @Test
    fun movesTheCategoryListAlongsideTheDatabase() = runTest {
        stack.repository.install(request())

        File(stack.repository.geoDirectory(), "geosite.json").exists() shouldBe true
    }

    // Validation runs against staging, never the live directory — otherwise a
    // corrupt download would have already overwritten a working file.
    @Test
    fun validatesInStagingNotInPlace() = runTest {
        stack.repository.install(request())

        validator.lastDir.shouldNotBeNull().parentFile?.name shouldBe "staging"
        validator.lastName shouldBe "geosite"
        validator.lastKind shouldBe GeoDataKind.DOMAIN
    }

    @Test
    fun aRejectedFileIsNeverInstalledAndTheFailureIsRecorded() = runTest {
        validator.answer = GeoValidation.NotGeoData

        stack.repository.install(request()) shouldBe GeoInstallResult.Rejected

        File(stack.repository.geoDirectory(), "geosite.dat").exists() shouldBe false
        stack.repository.observeAll().first().single().lastFailure shouldBe "NotGeoData"
    }

    @Test
    fun aRejectedFileLeavesNothingInStaging() = runTest {
        validator.answer = GeoValidation.NotGeoData

        stack.repository.install(request())

        File(stack.repository.geoDirectory(), "staging").listFiles().orEmpty().size shouldBe 0
    }

    // The existing file must survive a failed replacement — a user who already
    // has working geo data must not lose it to a bad download.
    @Test
    fun aRejectedReplacementLeavesThePreviousFileIntact() = runTest {
        stack.repository.install(request())
        validator.answer = GeoValidation.NotGeoData

        stack.repository.install(request())

        File(stack.repository.geoDirectory(), "geosite.dat").readText() shouldBe "payload"
    }

    @Test
    fun aSuccessfulInstallClearsAPreviousFailureMarker() = runTest {
        validator.answer = GeoValidation.NotGeoData
        stack.repository.install(request())
        validator.answer = GeoValidation.Valid

        stack.repository.install(request())

        stack.repository.observeAll().first().single().lastFailure shouldBe null
    }

    @Test
    fun rejectsUnsafeFileNamesBeforeTheyCanTouchTheFilesystem() = runTest {
        val outside = File(stack.repository.geoDirectory().parentFile, "outside.dat").apply {
            writeText("keep")
        }

        listOf("", "../outside.dat", "/tmp/outside.dat", "dir/file.dat", "geosite.txt").forEach { name ->
            stack.repository.install(request(name)) shouldBe GeoInstallResult.Rejected
        }

        outside.readText() shouldBe "keep"
        File(stack.repository.geoDirectory(), "staging").exists() shouldBe false
    }

    @Test
    fun aValidDatabaseWithoutTheRequiredSidecarIsNotInstalled() = runTest {
        validator.writesSidecar = false

        stack.repository.install(request()) shouldBe GeoInstallResult.DownloadFailed

        File(stack.repository.geoDirectory(), "geosite.dat").exists() shouldBe false
        stack.repository.observeAll().first().single().lastFailure shouldBe "InstallFailed"
    }

    @Test
    fun anUnexpectedValidatorFailureIsRecordedAndLeavesNoStagingFiles() = runTest {
        validator.exception = IllegalStateException("unexpected")

        stack.repository.install(request()) shouldBe GeoInstallResult.DownloadFailed

        stack.repository.observeAll().first().single().lastFailure shouldBe "InstallFailed"
        stagingFiles() shouldBe emptyList()
    }

    @Test
    fun anUnexpectedDownloadFailureIsRecordedAndLeavesNoStagingFiles() = runTest {
        stack.close()
        stack =
            InMemoryGeoStack(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                validator = validator,
                download = { _, _ -> throw IllegalArgumentException("unexpected") },
            )

        stack.repository.install(request()) shouldBe GeoInstallResult.DownloadFailed

        stack.repository.observeAll().first().single().lastFailure shouldBe "DownloadFailed"
        stagingFiles() shouldBe emptyList()
    }

    @Test
    fun installedFileNamesReportsWhatIsOnDisk() = runTest {
        stack.repository.install(request("geosite.dat"))

        stack.repository.installedFileNames() shouldBe setOf("geosite.dat")
    }

    @Test
    fun missingLiveFilesAreNotReportedInstalledAndAreImmediatelyDue() = runTest {
        stack.repository.install(request())
        File(stack.repository.geoDirectory(), "geosite.dat").delete()

        stack.repository.installedFileNames() shouldBe emptySet()
        stack.repository.isDueForRefresh("geosite.dat", nowMillis = stack.now) shouldBe true
    }

    @Test
    fun parallelInstallsUseSeparateStagingDirectories() = runTest {
        coroutineScope {
            listOf("geosite.dat", "geoip.dat")
                .map { name -> async { stack.repository.install(request(name)) } }
                .awaitAll() shouldBe listOf(GeoInstallResult.Installed, GeoInstallResult.Installed)
        }

        val directories = validator.stagingDirectories.toList()
        directories.size shouldBe 2
        directories.map { it.absolutePath }.toSet().size shouldBe 2
        directories.all { it.parentFile?.name == "staging" } shouldBe true
    }

    @Test
    fun sameFileNameInstallsDoNotOverlap() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val downloadCalls = AtomicInteger()
        val inFlight = AtomicInteger()
        val maximumInFlight = AtomicInteger()
        stack.close()
        stack =
            InMemoryGeoStack(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                validator = validator,
                download = { _, target ->
                    downloadCalls.incrementAndGet()
                    val current = inFlight.incrementAndGet()
                    maximumInFlight.updateAndGet { maximum -> maxOf(maximum, current) }
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    target.writeText("payload")
                    inFlight.decrementAndGet()
                    7L to "digest"
                },
            )

        coroutineScope {
            val first = async { stack.repository.install(request()) }
            firstStarted.await()
            val second = async { stack.repository.install(request()) }
            withContext(Dispatchers.Default) { delay(CONCURRENCY_SETTLE_MILLIS) }
            downloadCalls.get() shouldBe 1
            releaseFirst.complete(Unit)

            awaitAll(first, second) shouldBe listOf(GeoInstallResult.Installed, GeoInstallResult.Installed)
        }

        maximumInFlight.get() shouldBe 1
    }

    @Test
    fun aFailedSuccessRecordRestoresThePreviousDatAndJsonPair() = runTest {
        var payload = "old payload"
        stack.close()
        stack =
            InMemoryGeoStack(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                validator = validator,
                download = { _, target ->
                    target.writeText(payload)
                    payload.length.toLong() to "digest-$payload"
                },
            )
        validator.sidecarContents = "{\"version\":\"old\"}"
        stack.repository.install(request()) shouldBe GeoInstallResult.Installed

        payload = "new payload"
        validator.sidecarContents = "{\"version\":\"new\"}"
        val failingDao =
            object : GeoAssetDao by stack.geoAssetDao() {
                override suspend fun upsert(entity: GeoAssetEntity) {
                    if (entity.lastFailure == null) error("simulated Room record failure")
                    stack.geoAssetDao().upsert(entity)
                }
            }

        stack.repositoryWith(failingDao).install(request()) shouldBe GeoInstallResult.DownloadFailed

        File(stack.repository.geoDirectory(), "geosite.dat").readText() shouldBe "old payload"
        File(stack.repository.geoDirectory(), "geosite.json").readText() shouldBe "{\"version\":\"old\"}"
        stack.repository.observeAll().first().single().lastFailure shouldBe "InstallFailed"
    }

    @Test
    fun aFailedRollbackRetainsForcedBackupsAndRecordsRecoveryFailed() = runTest {
        var payload = "old payload"
        stack.close()
        stack =
            InMemoryGeoStack(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                validator = validator,
                download = { _, target ->
                    target.writeText(payload)
                    payload.length.toLong() to "digest-$payload"
                },
            )
        validator.sidecarContents = "{\"version\":\"old\"}"
        stack.repository.install(request()) shouldBe GeoInstallResult.Installed

        payload = "new payload"
        validator.sidecarContents = "{\"version\":\"new\"}"
        val failingDao =
            object : GeoAssetDao by stack.geoAssetDao() {
                override suspend fun upsert(entity: GeoAssetEntity) {
                    if (entity.lastFailure == null) error("simulated Room record failure")
                    stack.geoAssetDao().upsert(entity)
                }
            }

        stack
            .repositoryWith(failingDao) { _, _ -> throw java.io.IOException("simulated rollback copy failure") }
            .install(request()) shouldBe GeoInstallResult.DownloadFailed

        stack.repository.observeAll().first().single().lastFailure shouldBe "RecoveryFailed"
        val recoveryFiles = stagingFiles().map { it.name }.toSet()
        recoveryFiles shouldBe setOf("geosite.dat.previous", "geosite.json.previous")
    }

    @Test
    fun aSameNameInstallFromAnotherProcessWaitsForTheOsFileLock() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "geo-lock-${System.nanoTime()}").apply { mkdirs() }
            val ready = File(root, "ready")
            val started = File(root, "started")
            val result = File(root, "result")
            val closed = File(root, "closed")
            val databaseName = "geo-lock-${System.nanoTime()}.db"
            val operation = LockInstallOperation(root, databaseName, ready, started, result)
            var connection: ServiceConnection? = null

            try {
                val lockDirectory = File(root, "locks").apply { mkdirs() }
                FileChannel.open(
                    File(lockDirectory, "${GeoAssetLockService.FILE_NAME}.lock").toPath(),
                    CREATE,
                    WRITE,
                ).use { channel ->
                    val heldLock = channel.lock()
                    try {
                        connection = bindLockInstaller(context, operation)
                        waitForFile(ready)
                        val writerPid = ready.readText().toInt()
                        if (writerPid == Process.myPid()) {
                            throw AssertionError("GeoAssetLockService ran in the test process")
                        }

                        delay(CROSS_PROCESS_SETTLE_MILLIS)
                        started.exists() shouldBe false
                        result.exists() shouldBe false
                    } finally {
                        heldLock.release()
                    }
                }

                waitForFile(result)
                result.readText() shouldBe GeoInstallResult.Installed.name
                started.readText().toInt() shouldBe ready.readText().toInt()
                // `result` is atomically renamed only after this marker is
                // closed. This immediate read is the completion barrier, not
                // an eventual second wait which could hide a bad ordering.
                closed.isFile shouldBe true
                closed.readText().toInt() shouldBe ready.readText().toInt()
            } finally {
                connection?.let(context::unbindService)
                context.deleteDatabase(operation.databaseName)
                root.deleteRecursively()
            }
        }
    }

    // ---- the weekly cap (§A.3.1) ----

    @Test
    fun anUninstalledFileIsAlwaysDue() = runTest {
        stack.repository.isDueForRefresh("geoip.dat", nowMillis = stack.now) shouldBe true
    }

    @Test
    fun aFileInstalledSixDaysAgoIsNotDue() = runTest {
        stack.repository.install(request())

        val sixDays = stack.now + 6L * 24 * 60 * 60 * 1000
        stack.repository.isDueForRefresh("geosite.dat", nowMillis = sixDays) shouldBe false
    }

    @Test
    fun aFileInstalledEightDaysAgoIsDue() = runTest {
        stack.repository.install(request())

        val eightDays = stack.now + 8L * 24 * 60 * 60 * 1000
        stack.repository.isDueForRefresh("geosite.dat", nowMillis = eightDays) shouldBe true
    }

    private fun stagingFiles(): List<File> =
        File(stack.repository.geoDirectory(), "staging").walkTopDown().filter { it.isFile }.toList()

    private fun bindLockInstaller(
        context: Context,
        operation: LockInstallOperation,
    ): ServiceConnection {
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        val intent =
            Intent(context, GeoAssetLockService::class.java)
                .putExtra(GeoAssetLockService.EXTRA_DATABASE_NAME, operation.databaseName)
                .putExtra(GeoAssetLockService.EXTRA_ROOT, operation.root.absolutePath)
                .putExtra(GeoAssetLockService.EXTRA_READY_FILE, operation.ready.absolutePath)
                .putExtra(GeoAssetLockService.EXTRA_STARTED_FILE, operation.started.absolutePath)
                .putExtra(GeoAssetLockService.EXTRA_RESULT_FILE, operation.result.absolutePath)
                .putExtra(GeoAssetLockService.EXTRA_CLOSED_FILE, File(operation.root, "closed").absolutePath)
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            throw AssertionError("GeoAssetLockService is not declared in the androidTest manifest")
        }
        return connection
    }

    private suspend fun waitForFile(file: File) {
        withTimeout(FILE_WAIT_TIMEOUT_MILLIS) {
            while (!file.isFile) delay(FILE_WAIT_POLL_MILLIS)
        }
    }

    private companion object {
        const val CONCURRENCY_SETTLE_MILLIS = 100L
        const val CROSS_PROCESS_SETTLE_MILLIS = 300L
        const val FILE_WAIT_TIMEOUT_MILLIS = 10_000L
        const val FILE_WAIT_POLL_MILLIS = 25L
    }

    private data class LockInstallOperation(
        val root: File,
        val databaseName: String,
        val ready: File,
        val started: File,
        val result: File,
    )
}
