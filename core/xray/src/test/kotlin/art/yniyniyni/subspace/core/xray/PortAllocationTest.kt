// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * `XrayController` calls the native `LibXray.invoke` directly and cannot run
 * on the JVM, so [allocateDistinctPorts] — the retry loop that gives
 * [XrayController.allocatePorts] its distinctness guarantee — is exercised
 * here directly with a faked `fetch`, the same seam-extraction
 * [ProxyHeadProbeTest] uses for [XrayPingApi].
 */
class PortAllocationTest {
    @Test
    fun `retries when the underlying call returns a duplicate`() =
        runTest {
            var calls = 0
            val ports =
                allocateDistinctPorts(2) { count ->
                    calls++
                    if (calls == 1) List(count) { 38411 } else listOf(38411, 38412)
                }

            ports shouldBe listOf(38411, 38412)
            calls shouldBe 2
        }

    @Test
    fun `a distinct result on the first attempt is returned without retrying`() =
        runTest {
            var calls = 0
            val ports =
                allocateDistinctPorts(2) { count ->
                    calls++
                    listOf(1000, 1001).take(count)
                }

            ports shouldBe listOf(1000, 1001)
            calls shouldBe 1
        }

    @Test
    fun `gives up after a bounded number of attempts rather than spinning forever`() {
        runTest {
            var calls = 0
            shouldThrow<XrayException> {
                allocateDistinctPorts(2) { count ->
                    calls++
                    List(count) { 38411 }
                }
            }
            // Bounded: a genuinely exhausted range must fail, not spin.
            (calls in 2..5) shouldBe true
        }
    }

    @Test
    fun `a short result from the source is treated as a failed attempt`() =
        runTest {
            var calls = 0
            val ports =
                allocateDistinctPorts(2) { count ->
                    calls++
                    if (calls == 1) listOf(1) else listOf(1, 2)
                }

            ports shouldBe listOf(1, 2)
            calls shouldBe 2
        }
}
