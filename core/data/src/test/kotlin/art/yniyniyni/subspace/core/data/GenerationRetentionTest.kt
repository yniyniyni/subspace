// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GenerationRetentionTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun deleteRequestAfterHoldersFinalMarkerCheckIsConsumedWithoutAnotherSweep() {
        val root = temp.newFolder("lost-delete-wakeup")
        val generation = File(root, "sets/7/1").apply { mkdirs() }
        File(generation, "geoip.dat").writeText("stale")
        val lockBackend = PausingLockBackend()
        val owner = GenerationRetentionRegistry(lockBackend)
        val deleter = GenerationRetentionRegistry(lockBackend)
        val deleteGeneration = {
            generation.deleteRecursively()
            !Files.exists(generation.toPath(), NOFOLLOW_LINKS)
        }
        val lease = owner.acquire(root, 7, 1, generation, deleteGeneration)
            ?: error("holder must acquire the first lock")
        val executor = Executors.newFixedThreadPool(2)

        try {
            val release = executor.submit { lease.release() }
            lockBackend.holderPassedFinalMarkerCheck.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

            val deletion =
                executor.submit(
                    Callable { deleter.requestDelete(root, 7, 1, generation, deleteGeneration) },
                )
            lockBackend.deleterWaitingForLock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            Files.exists(marker(root), NOFOLLOW_LINKS) shouldBe true

            lockBackend.allowHolderToUnlock.countDown()
            release.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

            Files.exists(generation.toPath(), NOFOLLOW_LINKS) shouldBe false
            Files.exists(marker(root), NOFOLLOW_LINKS) shouldBe false
        } finally {
            lockBackend.allowHolderToUnlock.countDown()
            executor.shutdownNow()
        }
    }

    private fun marker(root: File): Path =
        File(root, ".routing-generation-retention/7/1.delete").toPath()

    /**
     * Models two Android processes with one cross-process lock.
     *
     * The first handle pauses inside release, after the registry's last marker
     * read but before unlock. The second acquisition blocks like FileChannel.lock().
     */
    private class PausingLockBackend : GenerationFileLockBackend {
        val holderPassedFinalMarkerCheck = CountDownLatch(1)
        val deleterWaitingForLock = CountDownLatch(1)
        val allowHolderToUnlock = CountDownLatch(1)
        private val semaphore = Semaphore(1)
        private val acquisitions = AtomicInteger()

        override fun acquire(lockPath: Path): GenerationFileLock? {
            val number = acquisitions.incrementAndGet()
            if (number == 2) deleterWaitingForLock.countDown()
            semaphore.acquire()
            return GenerationFileLock {
                if (number == 1) {
                    holderPassedFinalMarkerCheck.countDown()
                    allowHolderToUnlock.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                semaphore.release()
            }
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
