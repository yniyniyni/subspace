// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * One dropped connection must not cost the whole refresh.
 *
 * M4's device run, reported as a reproducible habit: "when I try to update my sub I usually get
 * *could not reach the server*, but if I immediately tap refresh again it updates as expected."
 * Three facts make that the client's problem rather than the network's.
 *
 * The host has a single `A` record, no `AAAA`, and the device had no IPv6 route — so OkHttp has
 * exactly **one** route. Its own recovery works by advancing to the next one, which means a
 * connection-level failure on a single-route host is terminal inside the call. And this fetcher
 * made exactly one attempt and reported whatever came back. The user tapping again was performing,
 * by hand, the retry the app never attempted.
 *
 * The packet-level cause is deliberately not pinned down here — a stale NAT mapping on the
 * gateway, a reset injected in the path, and Wi-Fi power-save all produce this shape, and the
 * remedy is the same for all three. What these tests pin is the remedy: a connection that dies
 * before an answer arrives is retried once, and a server that answers is taken at its word.
 */
class SubscriptionFetcherRetryTest {
    private lateinit var server: MockWebServer

    private fun fetcher() = SubscriptionFetcher(HwidProvider { "TESTHWIDvalue0123456789" }, appVersion = "1.0.0")

    private fun request() = SubscriptionRequest(
        url = server.url("/sub").toString(),
        hwidEnabled = true,
        userAgentOverride = null,
        timeoutSeconds = 9,
    )

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.close() }

    @Test
    fun `a connection dropped before any answer is retried once and succeeds`() = runTest {
        // Exactly the device pattern: the first attempt gets nothing back, the second is fine.
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        server.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Success>()
        outcome.body shouldBe "vless://u@h:443"
        server.requestCount shouldBe 2
    }

    @Test
    fun `a host that is down every time still fails, and does not retry forever`() = runTest {
        // The retry is one attempt, not a loop. Nothing is enqueued, so every connection is
        // refused; the outcome must still be a plain failure and the call must still terminate.
        server.close()

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.Unreachable
    }

    @Test
    fun `a server that answers is taken at its word and never retried`() = runTest {
        // A 404 or a 500 is a considered answer, not a dropped connection. Retrying it would
        // double the load on a provider that is already struggling and would not change the
        // result — only connection-level failures get a second chance.
        server.enqueue(MockResponse(code = 500))

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.ServerError
        server.requestCount shouldBe 1
    }

    @Test
    fun `a device-limit answer is not retried either`() = runTest {
        // §7's HWID outcomes arrive on a 200 with marker headers. They are answers, and retrying
        // one would ask a panel to re-count a device that is already at its cap.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("x-hwid-max-devices-reached", "true")
                .build(),
        )

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.DeviceLimitReached
        server.requestCount shouldBe 1
    }

    @Test
    fun `a timeout is not retried, because the budget is already spent`() = runTest {
        // Retrying here would double the worst case the user waits through for a server that has
        // already shown it will not answer in time. The scheduler's own backoff covers it.
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())

        val outcome = fetcher().fetch(request().copy(timeoutSeconds = 1))

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.TimedOut
        server.requestCount shouldBe 1
    }
}
