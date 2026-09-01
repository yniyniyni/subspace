// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

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
            // Pins the bound exactly. `calls in 2..5` would still pass if the
            // bound silently changed from 3 to 5, and would report an
            // uninformative "false is not true" rather than "expected 3 but
            // was 5" if it ever moved (task-12 review, Finding 3).
            calls shouldBe 3
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

    /**
     * Task-12 review, Finding 2: an over-long list where the *extra* entries
     * are themselves duplicates — `[1, 1, 2]` for `count = 2` — has
     * `toSet().size == count` (the unique values are exactly `{1, 2}`), so
     * `ports.toSet().size == count` alone accepts it. A plain over-long
     * distinct list like `[1, 2, 3]` for `count = 2` does not exercise this:
     * its set size is 3, which already fails the distinctness check with or
     * without the `ports.size == count` clause. This is the shape that
     * actually needs the size clause to be rejected.
     */
    @Test
    fun `an over-long result padded with a duplicate is treated as a failed attempt`() =
        runTest {
            var calls = 0
            val ports =
                allocateDistinctPorts(2) { count ->
                    calls++
                    if (calls == 1) listOf(1, 1, 2) else listOf(4, 5)
                }

            ports shouldBe listOf(4, 5)
            calls shouldBe 2
        }
}
