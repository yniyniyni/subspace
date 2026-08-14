// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.tunnel

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.FailureReason
import art.yniyniyni.subspace.core.model.StartupStage
import art.yniyniyni.subspace.core.model.failure
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * §5.4's fallback rule, which is the one thing here that can fail quietly.
 *
 * A wrong answer does not throw: it makes a fetch dial a port nothing is
 * listening on (connection refused, reported as an ordinary network failure) or
 * silently go direct when the user expected the tunnel. Neither produces a stack
 * trace pointing back here.
 */
class TunnelProxyBindingTest {
    @Test
    fun `a connected tunnel with an http inbound offers its port`() {
        val state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080, httpProxyPort = 1081)

        state.httpProxyPortOrNull() shouldBe 1081
    }

    /**
     * Zero is the `Connected` default and means "this session carries no HTTP
     * inbound" — not "connected on port 0". Returning it would have every fetch
     * dial a port that cannot exist.
     */
    @Test
    fun `a connected tunnel without an http inbound offers nothing`() {
        val state = ConnectionState.Connected(sinceEpochMillis = 1L, socksPort = 1080, httpProxyPort = 0)

        state.httpProxyPortOrNull() shouldBe null
    }

    /**
     * Every other state means there is no proxy to dial. A background refresh
     * with no bound service sees `Disconnected` and must fetch directly — that
     * fallback is what stops a broken tunnel from also breaking subscription
     * updates.
     */
    @Test
    fun `no other state offers a port`() {
        val states =
            listOf(
                ConnectionState.Disconnected,
                ConnectionState.Connecting(StartupStage.AllocatingPort),
                ConnectionState.Disconnecting,
                failure(FailureReason.CoreStartFailed, "boom"),
            )

        states.forEach { state ->
            withClue(state::class.simpleName.orEmpty()) { state.httpProxyPortOrNull() shouldBe null }
        }
    }
}
