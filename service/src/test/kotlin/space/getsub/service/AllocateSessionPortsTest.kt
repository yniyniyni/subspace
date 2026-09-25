// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.getsub.core.xray.XrayException

/**
 * `allocateSessionPorts` is the real decision `startCore` runs — replacing
 * `metricsPortFor`, which asserted the invariant below against a lambda
 * `startCore` did not actually call. A second, independent allocation call
 * for the metrics port broke that invariant by construction: nothing keeps
 * two separate `getFreePorts` calls from returning the same number (each one
 * binds `localhost:0` and closes the listener before the next), so a
 * collision with the socks/http pair failed the whole connect for an
 * optional counter. These tests exercise the one-call-or-degrade replacement
 * directly, with a faked allocator — `XrayController.allocatePorts` itself
 * calls native `LibXray.invoke` and cannot run on the JVM (`PortAllocationTest`
 * uses the same seam for `allocateDistinctPorts`).
 */
class AllocateSessionPortsTest {
    @Test
    fun `the breakdown off requests only the two required ports`() =
        runTest {
            val requests = mutableListOf<Int>()
            val result =
                allocateSessionPorts(breakdownEnabled = false) { count ->
                    requests += count
                    listOf(100, 101).take(count)
                }

            assertEquals(listOf(2), requests)
            assertEquals(SessionPorts(socksPort = 100, httpPort = 101, metricsPort = null), result)
        }

    @Test
    fun `the breakdown on requests all three ports in one call`() =
        runTest {
            val requests = mutableListOf<Int>()
            val result =
                allocateSessionPorts(breakdownEnabled = true) { count ->
                    requests += count
                    listOf(200, 201, 202).take(count)
                }

            // One call, not two: a second, independent call is exactly the
            // bug this function replaced (see the class KDoc).
            assertEquals(listOf(3), requests)
            assertEquals(SessionPorts(socksPort = 200, httpPort = 201, metricsPort = 202), result)
        }

    @Test
    fun `an allocation failure disables the breakdown rather than the session`() =
        runTest {
            val requests = mutableListOf<Int>()
            val result =
                allocateSessionPorts(breakdownEnabled = true) { count ->
                    requests += count
                    if (count == 3) throw XrayException("no free ports") else listOf(300, 301)
                }

            // Falls back to the required pair; the connect proceeds with no
            // breakdown rather than failing.
            assertEquals(listOf(3, 2), requests)
            assertEquals(SessionPorts(socksPort = 300, httpPort = 301, metricsPort = null), result)
        }

    @Test
    fun `a non-XrayException from the three-port attempt still degrades, not just XrayException`() =
        runTest {
            // fetchFreePorts's own JSON parsing (JSONObject/JSONArray) throws
            // org.json.JSONException, not XrayException — a catch narrower than
            // Exception here would let that one escape and fail the connect,
            // which is the exact defect a prior version of this code had.
            val requests = mutableListOf<Int>()
            val result =
                allocateSessionPorts(breakdownEnabled = true) { count ->
                    requests += count
                    if (count == 3) error("malformed response") else listOf(400, 401)
                }

            assertEquals(listOf(3, 2), requests)
            assertEquals(SessionPorts(socksPort = 400, httpPort = 401, metricsPort = null), result)
        }

    @Test
    fun `a failure allocating the required pair is never swallowed`() =
        runTest {
            // Both attempts fail: the fallback two-port request's own failure
            // must propagate uncaught, exactly as XrayController.allocatePorts's
            // documented contract already promises — only the optional third
            // port degrades.
            shouldThrow<XrayException> {
                allocateSessionPorts(breakdownEnabled = true) { count ->
                    if (count == 3) throw XrayException("no free ports") else throw XrayException("still none")
                }
            }
        }

    @Test
    fun `cancellation during the three-port attempt is not swallowed as a degrade`() =
        runTest {
            shouldThrow<CancellationException> {
                allocateSessionPorts(breakdownEnabled = true) { count ->
                    if (count == 3) throw CancellationException("cancelled") else listOf(1, 2)
                }
            }
        }

    @Test
    fun `the breakdown off never calls the allocator with a three-port request`() =
        runTest {
            val requests = mutableListOf<Int>()
            allocateSessionPorts(breakdownEnabled = false) { count ->
                requests += count
                listOf(1, 2).take(count)
            }

            assertNull(requests.find { it == 3 })
        }
}
