// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

class RuleSetAssetsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun assets(): RuleSetAssets = RuleSetAssets(temp.newFolder("geo"))

    @Test
    fun prepareGenerationYieldsAnEmptyDirectory() = runTest {
        val dir = assets().prepareGeneration(setId = 7, generation = 1)

        dir.isDirectory shouldBe true
        dir.listFiles()?.size shouldBe 0
        dir.absolutePath.endsWith("sets/7/1") shouldBe true
    }

    @Test
    fun generationDirRejectsZeroAndNegativeGenerations() {
        val subject = assets()

        listOf(0L, -1L).forEach { generation ->
            shouldThrow<IllegalArgumentException> {
                subject.generationDir(setId = 7, generation = generation)
            }
        }
    }

    @Test
    fun prepareGenerationRejectsZeroAndNegativeGenerations() = runTest {
        val subject = assets()

        listOf(0L, -1L).forEach { generation ->
            shouldThrow<IllegalArgumentException> {
                subject.prepareGeneration(setId = 7, generation = generation)
            }
        }
    }

    @Test
    fun preparingTheSameGenerationTwiceStartsFromEmpty() = runTest {
        val subject = assets()
        val first = subject.prepareGeneration(7, 1)
        File(first, "geoip.dat").writeText("stale")

        val second = subject.prepareGeneration(7, 1)

        second.listFiles()?.size shouldBe 0
    }

    @Test
    fun anOldGenerationSurvivesUntilItIsSweptExplicitly() = runTest {
        val subject = assets()
        File(subject.prepareGeneration(7, 1), "geoip.dat").writeText("live")
        File(subject.prepareGeneration(7, 2), "geoip.dat").writeText("next")

        subject.generationDir(7, 1).isDirectory shouldBe true
        subject.generationDir(7, 2).isDirectory shouldBe true

        subject.sweepExcept(setId = 7, keep = 2)

        subject.generationDir(7, 1).exists() shouldBe false
        subject.generationDir(7, 2).isDirectory shouldBe true
    }

    @Test
    fun sweepingDuringASeparateInstanceLeaseDefersDeletionUntilFinalRelease() = runTest {
        val root = temp.newFolder("leased-generation")
        val owner = RuleSetAssets(root)
        val sweeper = RuleSetAssets(root)
        File(owner.prepareGeneration(7, 1), "geoip.dat").writeText("startup")
        File(owner.prepareGeneration(7, 2), "geoip.dat").writeText("new-live")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val startup =
            launch {
                owner.withResolvedAssetDir(7, 1, hasOwnSources = true) {
                    entered.complete(Unit)
                    release.await()
                } shouldBe ResolvedAssetUse.Used(Unit)
            }
        entered.await()

        sweeper.sweepExcept(setId = 7, keep = 2)

        owner.generationDir(7, 1).isDirectory shouldBe true
        owner.generationDir(7, 2).isDirectory shouldBe true

        release.complete(Unit)
        startup.join()

        owner.generationDir(7, 1).exists() shouldBe false
        owner.generationDir(7, 2).isDirectory shouldBe true
    }

    @Test
    fun cancellingAResolvedAssetScopeReleasesItsGenerationLease() = runTest {
        val root = temp.newFolder("cancelled-lease")
        val owner = RuleSetAssets(root)
        val sweeper = RuleSetAssets(root)
        owner.prepareGeneration(7, 1)
        owner.prepareGeneration(7, 2)
        val entered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        val startup =
            launch {
                owner.withResolvedAssetDir(7, 1, hasOwnSources = true) {
                    entered.complete(Unit)
                    neverRelease.await()
                }
            }
        entered.await()

        startup.cancelAndJoin()
        sweeper.sweepExcept(setId = 7, keep = 2)

        owner.generationDir(7, 1).exists() shouldBe false
    }

    @Test
    fun aFailedResolvedAssetScopeReleasesItsGenerationLease() = runTest {
        val root = temp.newFolder("failed-lease")
        val owner = RuleSetAssets(root)
        val sweeper = RuleSetAssets(root)
        owner.prepareGeneration(7, 1)
        owner.prepareGeneration(7, 2)

        shouldThrow<IllegalStateException> {
            owner.withResolvedAssetDir(7, 1, hasOwnSources = true) {
                throw IllegalStateException("expected test failure")
            }
        }
        sweeper.sweepExcept(setId = 7, keep = 2)

        owner.generationDir(7, 1).exists() shouldBe false
    }

    @Test
    fun aLaterLeaseAttemptReapsAStaleDeleteMarkerAfterTakingTheOsLock() = runTest {
        val root = temp.newFolder("stale-marker")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        val retention = File(root, ".routing-generation-retention/7").apply(File::mkdirs)
        File(retention, "1.delete").writeText("")

        subject.withResolvedAssetDir(7, 1, hasOwnSources = true) { error("must not expose stale generation") } shouldBe
            ResolvedAssetUse.GenerationUnavailable

        subject.generationDir(7, 1).exists() shouldBe false
        File(retention, "1.delete").exists() shouldBe false
    }

    @Test
    fun aSymlinkedDeleteMarkerIsRemovedWithoutTouchingItsDestination() = runTest {
        val root = temp.newFolder("symlinked-marker")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        val external = temp.newFile("external-marker-target").apply { writeText("outside") }
        val retention = File(root, ".routing-generation-retention/7").apply(File::mkdirs)
        val marker = File(retention, "1.delete")
        Files.createSymbolicLink(marker.toPath(), external.toPath())

        subject.withResolvedAssetDir(7, 1, hasOwnSources = true) { error("must not expose stale generation") } shouldBe
            ResolvedAssetUse.GenerationUnavailable

        external.readText() shouldBe "outside"
        Files.exists(marker.toPath(), NOFOLLOW_LINKS) shouldBe false
    }

    @Test
    fun aStaleMarkerReapsABrokenGenerationSymlinkWithoutFollowingIt() = runTest {
        val root = temp.newFolder("broken-generation-link")
        val subject = RuleSetAssets(root)
        val generation = subject.generationDir(7, 1)
        generation.parentFile?.mkdirs()
        Files.createSymbolicLink(generation.toPath(), File(temp.root, "missing-target").toPath())
        val retention = File(root, ".routing-generation-retention/7").apply(File::mkdirs)
        File(retention, "1.delete").writeText("")

        subject.withResolvedAssetDir(7, 1, hasOwnSources = true) { error("must not expose stale generation") } shouldBe
            ResolvedAssetUse.GenerationUnavailable

        Files.exists(generation.toPath(), NOFOLLOW_LINKS) shouldBe false
    }

    @Test
    fun aSymlinkedRetentionSetDirectoryCannotWriteAMarkerOutsideTheGeoRoot() = runTest {
        val root = temp.newFolder("symlinked-retention-parent")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        subject.prepareGeneration(7, 2)
        val external = temp.newFolder("external-retention").apply {
            File(this, "keep").writeText("outside")
        }
        val retentionRoot = File(root, ".routing-generation-retention").apply(File::mkdirs)
        Files.createSymbolicLink(File(retentionRoot, "7").toPath(), external.toPath())

        subject.sweepExcept(setId = 7, keep = 2)

        File(external, "keep").readText() shouldBe "outside"
        external.listFiles().orEmpty().map(File::getName) shouldBe listOf("keep")
        subject.generationDir(7, 1).isDirectory shouldBe true
    }

    @Test
    fun aSymlinkedLockPathMakesLeaseAcquisitionFailClosedWithoutReplacingIt() = runTest {
        val root = temp.newFolder("symlinked-acquire-lock")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        val external = temp.newFile("external-acquire-lock-target").apply { writeText("outside") }
        val retention = File(root, ".routing-generation-retention/7").apply(File::mkdirs)
        val lockPath = File(retention, "1.lock")
        Files.createSymbolicLink(lockPath.toPath(), external.toPath())

        subject.withResolvedAssetDir(7, 1, hasOwnSources = true) {
            error("unsafe lock must not expose assets")
        } shouldBe ResolvedAssetUse.GenerationUnavailable

        Files.isSymbolicLink(lockPath.toPath()) shouldBe true
        Files.readSymbolicLink(lockPath.toPath()) shouldBe external.toPath()
        external.readText() shouldBe "outside"
        subject.generationDir(7, 1).isDirectory shouldBe true
    }

    @Test
    fun aSymlinkedLockPathMakesSweepFailClosedWithoutReplacingIt() = runTest {
        val root = temp.newFolder("symlinked-delete-lock")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        subject.prepareGeneration(7, 2)
        val external = temp.newFile("external-delete-lock-target").apply { writeText("outside") }
        val retention = File(root, ".routing-generation-retention/7").apply(File::mkdirs)
        val lockPath = File(retention, "1.lock")
        Files.createSymbolicLink(lockPath.toPath(), external.toPath())

        subject.sweepExcept(setId = 7, keep = 2)

        Files.isSymbolicLink(lockPath.toPath()) shouldBe true
        Files.readSymbolicLink(lockPath.toPath()) shouldBe external.toPath()
        external.readText() shouldBe "outside"
        subject.generationDir(7, 1).isDirectory shouldBe true
        Files.exists(File(retention, "1.delete").toPath(), NOFOLLOW_LINKS) shouldBe true
    }

    @Test
    fun aSymlinkedRetentionParentMakesLeaseAcquisitionFailClosed() = runTest {
        val root = temp.newFolder("symlinked-acquire-parent")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        val external = temp.newFolder("external-acquire-retention").apply {
            File(this, "keep").writeText("outside")
        }
        val retentionRoot = File(root, ".routing-generation-retention").apply(File::mkdirs)
        Files.createSymbolicLink(File(retentionRoot, "7").toPath(), external.toPath())

        subject.withResolvedAssetDir(7, 1, hasOwnSources = true) {
            error("unsafe parent must not expose assets")
        } shouldBe ResolvedAssetUse.GenerationUnavailable

        File(external, "keep").readText() shouldBe "outside"
        external.listFiles().orEmpty().map(File::getName) shouldBe listOf("keep")
        subject.generationDir(7, 1).isDirectory shouldBe true
    }

    @Test
    fun anOverlappingExternalLockIsTreatedAsLeasedAndReapedLater() = runTest {
        val root = temp.newFolder("overlapping-lock")
        val subject = RuleSetAssets(root)
        subject.prepareGeneration(7, 1)
        subject.prepareGeneration(7, 2)
        val retention = File(root, ".routing-generation-retention/7").apply(File::mkdirs)
        val lockFile = File(retention, "1.lock")
        FileChannel.open(lockFile.toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use {
                subject.sweepExcept(setId = 7, keep = 2)
                subject.generationDir(7, 1).isDirectory shouldBe true
            }
        }

        subject.sweepExcept(setId = 7, keep = 2)

        subject.generationDir(7, 1).exists() shouldBe false
    }

    @Test
    fun sweepExceptRejectsZeroAndNegativeKeptGenerations() = runTest {
        val subject = assets()

        listOf(0L, -1L).forEach { keep ->
            shouldThrow<IllegalArgumentException> { subject.sweepExcept(setId = 7, keep = keep) }
        }
    }

    @Test
    fun removingASetRemovesEveryGeneration() = runTest {
        val subject = assets()
        subject.prepareGeneration(7, 1)
        subject.prepareGeneration(7, 2)

        subject.removeSet(7)

        File(subject.setsRoot(), "7").exists() shouldBe false
    }

    @Test
    fun removingAnInvalidSetIdLeavesTheSetsRootUntouched() = runTest {
        val subject = assets()
        val unrelatedSet = File(subject.setsRoot(), "8").apply { mkdirs() }

        shouldThrow<IllegalArgumentException> { subject.removeSet(0) }

        unrelatedSet.isDirectory shouldBe true
    }

    @Test
    fun resolveAssetDirFallsBackToTheSharedRootWhenTheSetHasNoSources() {
        val subject = assets()

        subject.resolveAssetDir(setId = 7, generation = 0, hasOwnSources = false) shouldBe
            subject.sharedRoot()
    }

    @Test
    fun resolveAssetDirUsesTheExactGenerationWhenTheSetHasSources() {
        val subject = assets()

        subject.resolveAssetDir(setId = 7, generation = 3, hasOwnSources = true) shouldBe
            File(subject.setsRoot(), "7/3")
    }

    @Test
    fun ownSourceAssetResolutionRejectsZeroAndNegativeGenerations() {
        val subject = assets()

        listOf(0L, -1L).forEach { generation ->
            shouldThrow<IllegalArgumentException> {
                subject.resolveAssetDir(setId = 7, generation = generation, hasOwnSources = true)
            }
        }
    }

    @Test
    fun preparingASymlinkedGenerationRemovesOnlyTheLink() = runTest {
        val subject = assets()
        val external = temp.newFolder("external-generation").apply {
            File(this, "keep.dat").writeText("outside")
        }
        val generation = subject.generationDir(7, 1)
        generation.parentFile?.mkdirs()
        Files.createSymbolicLink(generation.toPath(), external.toPath())

        subject.prepareGeneration(7, 1)

        File(external, "keep.dat").readText() shouldBe "outside"
        Files.isSymbolicLink(generation.toPath()) shouldBe false
        generation.isDirectory shouldBe true
    }

    @Test
    fun preparingWithASymlinkedSetAncestorFailsWithoutCreatingOutsideTheSetsRoot() = runTest {
        val subject = assets()
        val external = temp.newFolder("external-ancestor").apply {
            File(this, "keep.dat").writeText("outside")
        }
        val setDirectory = File(subject.setsRoot(), "7")
        setDirectory.parentFile?.mkdirs()
        Files.createSymbolicLink(setDirectory.toPath(), external.toPath())

        shouldThrow<IOException> { subject.prepareGeneration(7, 1) }

        File(external, "keep.dat").readText() shouldBe "outside"
        File(external, "1").exists() shouldBe false
        Files.isSymbolicLink(setDirectory.toPath()) shouldBe true
    }

    @Test
    fun sweepingASymlinkedGenerationRemovesOnlyTheLink() = runTest {
        val subject = assets()
        val external = temp.newFolder("external-sweep").apply {
            File(this, "keep.dat").writeText("outside")
        }
        val setDirectory = File(subject.setsRoot(), "7").apply { mkdirs() }
        val sweptGeneration = File(setDirectory, "1")
        Files.createSymbolicLink(sweptGeneration.toPath(), external.toPath())
        File(setDirectory, "2").mkdirs()

        subject.sweepExcept(setId = 7, keep = 2)

        File(external, "keep.dat").readText() shouldBe "outside"
        Files.exists(sweptGeneration.toPath(), NOFOLLOW_LINKS) shouldBe false
    }

    @Test
    fun removingASymlinkedSetRemovesOnlyTheLink() = runTest {
        val subject = assets()
        val external = temp.newFolder("external-set").apply {
            File(this, "keep.dat").writeText("outside")
        }
        val setDirectory = File(subject.setsRoot(), "7")
        setDirectory.parentFile?.mkdirs()
        Files.createSymbolicLink(setDirectory.toPath(), external.toPath())

        subject.removeSet(7)

        File(external, "keep.dat").readText() shouldBe "outside"
        Files.exists(setDirectory.toPath(), NOFOLLOW_LINKS) shouldBe false
    }

    @Test
    fun copyLocallyReproducesTheBytes() = runTest {
        val subject = assets()
        val source = temp.newFile("src.dat").apply { writeBytes(ByteArray(4096) { 7 }) }
        val target = File(subject.prepareGeneration(9, 1), "geoip.dat")

        subject.copyLocally(source, target) shouldBe true

        target.readBytes().contentEquals(source.readBytes()) shouldBe true
    }

    @Test
    fun copyLocallyReportsFailureAndRemovesThePartialTarget() = runTest {
        val subject = assets()
        val missing = File(temp.root, "does-not-exist.dat")
        val target = File(subject.prepareGeneration(9, 1), "geoip.dat")
        target.writeText("partial")

        subject.copyLocally(missing, target) shouldBe false

        target.exists() shouldBe false
    }

    @Test
    fun copyFailureRemovesASymlinkTargetWithoutTouchingItsDestination() = runTest {
        val subject = assets()
        val external = temp.newFile("external-copy.dat").apply { writeText("outside") }
        val target = File(subject.prepareGeneration(9, 1), "geoip.dat")
        Files.createSymbolicLink(target.toPath(), external.toPath())

        subject.copyLocally(File(temp.root, "does-not-exist.dat"), target) shouldBe false

        external.readText() shouldBe "outside"
        Files.exists(target.toPath(), NOFOLLOW_LINKS) shouldBe false
    }

    @Test
    fun cancellationAtACopyChunkBoundaryRemovesThePartialTarget() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val neverRelease = CompletableDeferred<Unit>()
            val copier =
                CooperativeRuleSetFileCopier { copiedBytes ->
                    if (copiedBytes >= COPY_CHUNK_BYTES) {
                        entered.complete(Unit)
                        neverRelease.await()
                    }
                }
            val subject = RuleSetAssets(temp.newFolder("cooperative-copy"), copier)
            val source = temp.newFile("large-source.dat").apply {
                writeBytes(ByteArray(COPY_CHUNK_BYTES * 2) { 7 })
            }
            val target = File(subject.prepareGeneration(9, 1), "geoip.dat")
            val copying = launch { subject.copyLocally(source, target) }
            entered.await()

            copying.cancelAndJoin()

            target.exists() shouldBe false
        }
    }

    @Test
    fun validatedDigestMetadataLivesOnlyBesideItsStagedGenerationFile() = runTest {
        val subject = assets()
        val generation = subject.prepareGeneration(9, 1)
        val data = File(generation, "geoip.dat").apply { writeText("validated bytes") }

        subject.recordValidatedFile(9, 1, "geoip.dat") shouldBe true

        subject.verifiedGenerationFile(9, 1, "geoip.dat") shouldBe data
        val internalFiles =
            subject.setsRoot().walkTopDown().filter { it.isFile && it != data }.toList()
        internalFiles.size shouldBe 1
        internalFiles.single().parentFile shouldBe generation
        subject.sharedRoot().listFiles().orEmpty().none { it.name.endsWith(".sha256") } shouldBe true
    }

    @Test
    fun verifiedGenerationReturnsNullWhenTheCandidateBecomesUnreadable() = runTest {
        val subject = assets()
        val generation = subject.prepareGeneration(9, 1)
        val data = File(generation, "geoip.dat").apply { writeText("validated bytes") }
        subject.recordValidatedFile(9, 1, "geoip.dat") shouldBe true
        data.setReadable(false, false) shouldBe true

        try {
            subject.verifiedGenerationFile(9, 1, "geoip.dat") shouldBe null
        } finally {
            data.setReadable(true, false)
        }
    }

    private companion object {
        const val COPY_CHUNK_BYTES = 64 * 1024
    }
}
