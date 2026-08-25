// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ForegroundRefreshRunnerTest {
    @Test
    fun `refresh failure is reported by class and reschedule still runs once`() =
        runTest {
            val transcript = mutableListOf<String>()
            val reported = mutableListOf<String>()

            runForegroundRefresh(
                refresh = {
                    transcript += "refresh"
                    error("secret profile content")
                },
                reschedule = { transcript += "reschedule" },
                reportFailure = reported::add,
            )

            transcript shouldBe listOf("refresh", "reschedule")
            reported shouldBe listOf(IllegalStateException::class.java.name)
        }

    @Test
    fun `reschedule failure is reported by class and does not escape`() =
        runTest {
            var rescheduleAttempts = 0
            val reported = mutableListOf<String>()

            runForegroundRefresh(
                refresh = {},
                reschedule = {
                    rescheduleAttempts++
                    throw SecurityException("secret subscription url")
                },
                reportFailure = reported::add,
            )

            rescheduleAttempts shouldBe 1
            reported shouldBe listOf(SecurityException::class.java.name)
        }

    @Test
    fun `refresh and reschedule failures are both reported without escaping`() =
        runTest {
            var rescheduleAttempts = 0
            val reported = mutableListOf<String>()

            runForegroundRefresh(
                refresh = { throw IllegalArgumentException("secret refresh input") },
                reschedule = {
                    rescheduleAttempts++
                    throw UnsupportedOperationException("secret schedule input")
                },
                reportFailure = reported::add,
            )

            rescheduleAttempts shouldBe 1
            reported shouldBe
                listOf(
                    IllegalArgumentException::class.java.name,
                    UnsupportedOperationException::class.java.name,
                )
        }

    @Test
    fun `refresh cancellation propagates after exactly one reschedule attempt`() =
        runTest {
            val cancellation = CancellationException("cancellation content")
            var rescheduleAttempts = 0
            val reported = mutableListOf<String>()
            var thrown: CancellationException? = null

            try {
                runForegroundRefresh(
                    refresh = { throw cancellation },
                    reschedule = { rescheduleAttempts++ },
                    reportFailure = reported::add,
                )
            } catch (error: CancellationException) {
                thrown = error
            }

            thrown shouldBe cancellation
            rescheduleAttempts shouldBe 1
            reported shouldBe emptyList()
        }

    @Test
    fun `reschedule cancellation propagates`() =
        runTest {
            val cancellation = CancellationException("cancellation content")
            var thrown: CancellationException? = null

            try {
                runForegroundRefresh(
                    refresh = {},
                    reschedule = { throw cancellation },
                    reportFailure = {},
                )
            } catch (error: CancellationException) {
                thrown = error
            }

            thrown shouldBe cancellation
        }

    @Test
    fun `dual cancellation preserves refresh precedence and suppresses reschedule cancellation`() =
        runTest {
            val refreshCancellation = CancellationException("refresh cancellation")
            val rescheduleCancellation = CancellationException("reschedule cancellation")
            var rescheduleAttempts = 0
            val reported = mutableListOf<String>()
            var thrown: CancellationException? = null

            try {
                runForegroundRefresh(
                    refresh = { throw refreshCancellation },
                    reschedule = {
                        rescheduleAttempts++
                        throw rescheduleCancellation
                    },
                    reportFailure = reported::add,
                )
            } catch (error: CancellationException) {
                thrown = error
            }

            thrown shouldBe refreshCancellation
            thrown?.suppressed?.toList() shouldBe listOf(rescheduleCancellation)
            rescheduleAttempts shouldBe 1
            reported shouldBe emptyList()
        }

    @Test
    fun `the same cancellation instance is never self-suppressed`() =
        runTest {
            val cancellation = CancellationException("shared cancellation")
            var thrown: CancellationException? = null

            try {
                runForegroundRefresh(
                    refresh = { throw cancellation },
                    reschedule = { throw cancellation },
                    reportFailure = {},
                )
            } catch (error: CancellationException) {
                thrown = error
            }

            thrown shouldBe cancellation
            thrown?.suppressed?.toList() shouldBe emptyList()
        }
}
