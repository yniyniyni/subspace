// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.LatencyOutcome
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.ServerSocket

/**
 * `TcpProbe` is the mode that works for every protocol, including the four
 * `:core:xray` cannot generate a config for — so it is also the only probe that
 * can be exercised end to end on the JVM, with a real listening socket.
 */
class TcpProbeTest {
    @Test
    fun `a reachable port yields OK with a non-negative delay`() =
        runTest {
            ServerSocket(0).use { server ->
                val result = TcpProbe().measure("127.0.0.1", server.localPort, timeoutSeconds = 2)
                result.outcome shouldBe LatencyOutcome.OK
                result.delayMillis shouldBeGreaterThanOrEqual 0
            }
        }

    @Test
    fun `an unresolvable host is UNREACHABLE, not a thrown exception`() =
        runTest {
            val result = TcpProbe().measure("no-such-host.invalid", 443, timeoutSeconds = 2)
            result.outcome shouldBe LatencyOutcome.UNREACHABLE
            result.delayMillis shouldBe 0
        }

    @Test
    fun `a closed port is UNREACHABLE rather than OK`() =
        runTest {
            // Bound to claim the port, then released — so the address resolves but
            // nothing is listening, which is the "server is down" case.
            val port = ServerSocket(0).use { it.localPort }
            val result = TcpProbe().measure("127.0.0.1", port, timeoutSeconds = 2)
            result.outcome shouldBe LatencyOutcome.UNREACHABLE
        }

    @Test
    fun `the protector is offered the socket before connect`() =
        runTest {
            var connectedWhenOffered = true
            val protector =
                TcpSocketProtector { socket ->
                    connectedWhenOffered = socket.isConnected
                    true
                }
            ServerSocket(0).use { server ->
                TcpProbe(protector = protector).measure("127.0.0.1", server.localPort, timeoutSeconds = 2)
            }
            connectedWhenOffered shouldBe false
        }

    @Test
    fun `the socket is bound before the protector sees it, so it has an fd to protect`() =
        runTest {
            // The regression guard for the §5.1 defect a device run exposed:
            // VpnService.protect(Socket) resolves the socket's file descriptor,
            // and an unbound socket has none yet — so protecting one marks
            // nothing and quietly reports success. The symptom was a measurement
            // taken with the tunnel up reading 1 ms to Singapore, because the
            // unprotected connect terminated at tun2socks on the phone.
            //
            // isBound is the observable proxy for "an fd exists": false here
            // means protect is a no-op, whatever it returns.
            var boundWhenOffered = false
            val protector =
                TcpSocketProtector { socket ->
                    boundWhenOffered = socket.isBound
                    true
                }
            ServerSocket(0).use { server ->
                TcpProbe(protector = protector).measure("127.0.0.1", server.localPort, timeoutSeconds = 2)
            }
            boundWhenOffered shouldBe true
        }
}
