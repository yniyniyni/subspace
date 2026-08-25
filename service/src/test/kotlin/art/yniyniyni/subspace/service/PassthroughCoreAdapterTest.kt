// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.xray.XrayException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files

/**
 * [validateOnCore] hoisted out of [ServiceModule.boundPassthroughValidator]'s `@Provides` lambda
 * (review Important 5) precisely so its file lifecycle and exception conversion can be pinned
 * here, on the JVM, without Android or a mocking library.
 */
class PassthroughCoreAdapterTest {
    // A fresh directory per test, isolated from anything else on the machine's shared temp dir —
    // asserting "empty afterward" would otherwise be racy against unrelated files.
    private fun freshCacheDir() = Files.createTempDirectory("passthrough-core-adapter-test").toFile()

    @Test
    fun `the core accepts, the composed bytes are what it saw, and the temp file is gone afterward`() =
        runTest {
            val dir = freshCacheDir()
            var seenText: String? = null

            val accepted = validateOnCore(dir, validate = { file -> seenText = file.readText() }, json = "{}")

            accepted shouldBe true
            seenText shouldBe "{}"
            dir.listFiles()?.toList().orEmpty().shouldBeEmpty()
        }

    @Test
    fun `a core refusal returns false, and the temp file is still gone`() =
        runTest {
            val dir = freshCacheDir()

            val accepted = validateOnCore(dir, validate = { throw XrayException("refused") }, json = "{}")

            accepted shouldBe false
            dir.listFiles()?.toList().orEmpty().shouldBeEmpty()
        }

    @Test
    fun `a non-core failure propagates rather than being reported as a verdict, temp file cleaned up`() =
        runTest {
            val dir = freshCacheDir()

            shouldThrow<IllegalStateException> {
                validateOnCore(dir, validate = { throw IllegalStateException("boom") }, json = "{}")
            }

            dir.listFiles()?.toList().orEmpty().shouldBeEmpty()
        }
}
