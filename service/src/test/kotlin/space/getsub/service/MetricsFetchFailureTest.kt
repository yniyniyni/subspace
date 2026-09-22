// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * F1 (2026-09-22 review): [fetchMetricsPayload] must not let a structurally
 * broken fetch (e.g. the platform refusing cleartext because
 * `network_security_config.xml` regressed) look identical, forever, to an
 * ordinary empty reading. These tests exercise the consecutive-failure
 * counter that makes that distinguishable, via the `warn` seam — never
 * `android.util.Log` directly, which is unmocked in this plain-JVM test
 * environment (see [fetchMetricsPayload]'s KDoc).
 */
class MetricsFetchFailureTest {
    /**
     * Values above 65535 are not a valid TCP port. [java.net.InetSocketAddress]'s
     * own constructor rejects one before any socket or timeout is involved, so
     * every call against it fails immediately and deterministically — no real
     * network I/O, no sleeps, no flakiness. Used for the counting tests below
     * that don't need a real success in the mix.
     */
    private val invalidPort = 999_999

    @Test
    fun `fewer than ten consecutive failures does not warn`() {
        val warnings = mutableListOf<String>()
        repeat(9) { fetchMetricsPayload(port = invalidPort + 1, timeoutMillis = 50, warn = warnings::add) }
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `the tenth consecutive failure warns exactly once, naming only the exception class`() {
        val warnings = mutableListOf<String>()
        repeat(10) { fetchMetricsPayload(port = invalidPort + 2, timeoutMillis = 50, warn = warnings::add) }
        assertEquals(1, warnings.size)
        assertTrue("expected the exception's simple class name in the warning", warnings[0].isNotBlank())
        // §5.6: never the port, the URL, or any payload/config content — only
        // the failing exception's class name and the fixed template text.
        assertFalse(warnings[0].contains("127.0.0.1"))
        assertFalse(warnings[0].contains("://"))
        assertFalse(warnings[0].contains((invalidPort + 2).toString()))
    }

    @Test
    fun `after warning fires the counter resets and needs ten more failures to warn again`() {
        val warnings = mutableListOf<String>()
        val port = invalidPort + 3
        repeat(19) { fetchMetricsPayload(port = port, timeoutMillis = 50, warn = warnings::add) }
        assertEquals(1, warnings.size)

        fetchMetricsPayload(port = port, timeoutMillis = 50, warn = warnings::add)

        assertEquals(2, warnings.size)
    }

    @Test
    fun `failure streaks on different ports do not interfere`() {
        val warnings = mutableListOf<String>()
        val portA = invalidPort + 4
        val portB = invalidPort + 5
        repeat(9) { fetchMetricsPayload(port = portA, timeoutMillis = 50, warn = warnings::add) }
        repeat(9) { fetchMetricsPayload(port = portB, timeoutMillis = 50, warn = warnings::add) }
        assertEquals("nine failures on each of two ports must not sum into one ten-failure streak", 0, warnings.size)
    }

    /**
     * A real loopback [HttpServer] (JDK-standard, `com.sun.net.httpserver`, no
     * new dependency) rather than a mock: [fetchMetricsPayload] is a real
     * `HttpURLConnection` GET, and a "success" here needs to be an actual
     * completed HTTP round trip for this test to mean anything. The server's
     * status code toggles between a 500 (which `HttpURLConnection.getInputStream()`
     * turns into an `IOException`, i.e. a [fetchMetricsPayload] failure) and a
     * 200 with a real body, on the *same* port, so the sequence below is one
     * continuous streak on one port — not two ports being compared.
     */
    @Test
    fun `a real success clears that port's failure streak`() {
        val statusCode = AtomicInteger(500)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/debug/vars") { exchange ->
            val code = statusCode.get()
            val body = if (code == 200) "{}".toByteArray() else ByteArray(0)
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val warnings = mutableListOf<String>()

            repeat(9) { fetchMetricsPayload(port = port, timeoutMillis = 1_000, warn = warnings::add) }
            assertEquals("nine failures alone must not yet warn", emptyList<String>(), warnings)

            statusCode.set(200)
            assertEquals("{}", fetchMetricsPayload(port = port, timeoutMillis = 1_000, warn = warnings::add))

            statusCode.set(500)
            repeat(9) { fetchMetricsPayload(port = port, timeoutMillis = 1_000, warn = warnings::add) }
            assertEquals(
                "the success above must have reset the streak — nine more failures " +
                    "after it should still be below the threshold, not eighteen combined",
                emptyList<String>(),
                warnings,
            )

            // One more (the 10th since the reset) proves the counter still
            // works after a reset, rather than having been disabled by one.
            fetchMetricsPayload(port = port, timeoutMillis = 1_000, warn = warnings::add)
            assertEquals(1, warnings.size)
        } finally {
            server.stop(0)
        }
    }
}
