// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.getsub.core.model.LatencyOutcome
import space.getsub.core.model.LatencyResult
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val MILLIS_PER_SECOND = 1_000
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Marks a [Socket] to bypass the VPN route.
 *
 * Separate from [SocketProtector], which is fd-based because that is the shape
 * libXray's `DialerController` hands us. `VpnService` has a `protect(Socket)`
 * overload, and going through it keeps `:core:xray` free of any `VpnService`
 * import — the same reason [SocketProtector] exists.
 */
public fun interface TcpSocketProtector {
    /** @return true if the socket was successfully protected. */
    public fun protect(socket: Socket): Boolean
}

/**
 * Times a TCP connect to a server.
 *
 * Works for **every** protocol, including the four `:core:xray` cannot generate
 * a config for — which is why it exists alongside [ProxyHeadProbe].
 *
 * What it cannot tell you: a REALITY server that accepts the TCP connection and
 * then rejects the handshake reports a fast result here. [ProxyHeadProbe] is the
 * mode that proves the proxy actually carries traffic.
 */
public class TcpProbe(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val protector: TcpSocketProtector? = null,
) {
    /**
     * Resolution happens **before** the clock starts, deliberately. Timing it too
     * would make a cold-cache and a warm-cache measurement incomparable, which
     * defeats the sort this feeds.
     */
    // Every catch below is a deliberate swallow, and the reason is §5.6 rather
    // than convenience: `UnknownHostException` and `SocketTimeoutException` put
    // the hostname in their message, and a server address is a secret. The
    // failure is not lost — it becomes a typed LatencyOutcome the UI renders its
    // own text for. Same reasoning as XrayController.stopBlocking's suppression.
    @Suppress("SwallowedException")
    public suspend fun measure(
        address: String,
        port: Int,
        timeoutSeconds: Int,
    ): LatencyResult =
        withContext(io) {
            val resolved =
                try {
                    InetAddress.getByName(address)
                } catch (e: UnknownHostException) {
                    // §5.6: the exception message contains the hostname. Never logged.
                    return@withContext LatencyResult.failed(LatencyOutcome.UNREACHABLE)
                }

            Socket().use { socket ->
                // Bind first, and this is load-bearing rather than tidiness.
                //
                // `VpnService.protect(Socket)` resolves the socket's file
                // descriptor to mark it. A bare `Socket()` is unbound and the JDK
                // creates that fd lazily — on bind or connect — so protecting one
                // here marks nothing and silently succeeds at doing nothing.
                // Binding to an ephemeral local port forces the fd to exist.
                //
                // Observed, not theorised: without this, a measurement taken while
                // the tunnel was up reported 1–6 ms for servers in Amsterdam,
                // Newark and Singapore. The unprotected connect went into the TUN,
                // where tun2socks accepts locally and returns immediately — so the
                // number was the round trip to the phone itself. §5.1's exact
                // signature, and SocketProtector's KDoc warns that a failed
                // protect surfaces only as a symptom.
                socket.bind(InetSocketAddress(0))

                // Before connect, never after: an unprotected connect is already
                // inside the TUN by the time it returns (§5.1).
                protector?.protect(socket)
                val start = System.nanoTime()
                try {
                    socket.connect(InetSocketAddress(resolved, port), timeoutSeconds * MILLIS_PER_SECOND)
                } catch (e: SocketTimeoutException) {
                    return@withContext LatencyResult.failed(LatencyOutcome.TIMEOUT)
                } catch (e: IOException) {
                    return@withContext LatencyResult.failed(LatencyOutcome.UNREACHABLE)
                }
                val elapsedMillis = (System.nanoTime() - start) / NANOS_PER_MILLI
                LatencyResult.ok(elapsedMillis.toInt())
            }
        }
}
