// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

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
}
