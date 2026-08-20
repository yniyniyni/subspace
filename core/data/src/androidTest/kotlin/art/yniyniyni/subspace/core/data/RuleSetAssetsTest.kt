// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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
}
