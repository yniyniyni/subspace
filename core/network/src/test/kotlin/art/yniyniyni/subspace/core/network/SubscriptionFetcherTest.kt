// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class SubscriptionFetcherTest {
    private lateinit var server: MockWebServer
    private val hwid = HwidProvider { "TESTHWIDvalue0123456789" }

    private fun fetcher() = SubscriptionFetcher(hwid, appVersion = "1.0.0")

    private fun request(timeoutSeconds: Int = 9) = SubscriptionRequest(
        url = server.url("/sub").toString(),
        hwidEnabled = true,
        userAgentOverride = null,
        timeoutSeconds = timeoutSeconds,
    )

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.close() }

    @Test
    fun `sends x-hwid and the device headers`() = runTest {
        server.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

        fetcher().fetch(request())

        val recorded = server.takeRequest()
        recorded.headers["x-hwid"] shouldBe "TESTHWIDvalue0123456789"
        recorded.headers["x-device-os"] shouldBe "Android"
        recorded.headers["x-app-version"] shouldBe "1.0.0"
        recorded.headers["user-agent"] shouldBe "Subspace/1.0.0"
    }

    @Test
    fun `omits x-hwid when the user has disabled it`() = runTest {
        server.enqueue(MockResponse(code = 200, body = ""))

        fetcher().fetch(request().copy(hwidEnabled = false))

        server.takeRequest().headers["x-hwid"] shouldBe null
    }

    @Test
    fun `a per-subscription override replaces the User-Agent`() = runTest {
        // §A.4.2: Remnawave's response rules match on user-agent and serve a
        // different format per client, so the override is load-bearing.
        server.enqueue(MockResponse(code = 200, body = ""))

        fetcher().fetch(request().copy(userAgentOverride = "v2rayNG/1.8.5"))

        server.takeRequest().headers["user-agent"] shouldBe "v2rayNG/1.8.5"
    }

    @Test
    fun `404 with x-hwid-not-supported is HwidRequired, not NotFound`() = runTest {
        // The milestone's exit criterion. Remnawave returns 404 both for a
        // missing HWID header and for a wrong URL; only a response header tells
        // them apart, and a bare 404 is what most clients give today (§A.4.1).
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .addHeader("x-hwid-active", "true")
                .addHeader("x-hwid-not-supported", "true")
                .build(),
        )

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.HwidRequired)
    }

    @Test
    fun `404 without that header is NotFound`() = runTest {
        server.enqueue(MockResponse(code = 404))

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.NotFound)
    }

    @Test
    fun `x-hwid-max-devices-reached is DeviceLimitReached`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("x-hwid-active", "true")
                .addHeader("x-hwid-max-devices-reached", "true")
                .build(),
        )

        fetcher().fetch(request()) shouldBe
            FetchOutcome.Failed(FetchFailure.DeviceLimitReached)
    }

    @Test
    fun `x-hwid-limit is treated the same, for v2RayTun compatibility`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("x-hwid-limit", "true")
                .build(),
        )

        fetcher().fetch(request()) shouldBe
            FetchOutcome.Failed(FetchFailure.DeviceLimitReached)
    }

    @Test
    fun `a successful response returns the body and its headers`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("profile-title", "Name VPN")
                .body("vless://u@h:443")
                .build(),
        )

        val outcome = fetcher().fetch(request())

        // The fetcher returns every response header, unfiltered (KDoc on
        // FetchOutcome.Success says so): the directive registry is the
        // allow-list, and filtering here as well as there would hide where
        // the filtering actually happens. That means this response also
        // carries OkHttp/MockWebServer's own headers (content-length and
        // so on), so this asserts the one header under test rather than
        // the whole map.
        (outcome as FetchOutcome.Success).body shouldBe "vless://u@h:443"
        outcome.headers["profile-title"] shouldBe "Name VPN"
    }

    @Test
    fun `4xx and 5xx are distinct failures`() = runTest {
        server.enqueue(MockResponse(code = 403))
        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.ClientError)

        server.enqueue(MockResponse(code = 502))
        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.ServerError)
    }

    @Test
    fun `an unreachable host is Unreachable, not a crash`() = runTest {
        val outcome = fetcher().fetch(
            SubscriptionRequest(
                url = "https://localhost:1/sub",
                hwidEnabled = true,
                userAgentOverride = null,
                timeoutSeconds = 5,
            ),
        )

        outcome shouldBe FetchOutcome.Failed(FetchFailure.Unreachable)
    }

    @Test
    fun `no failure carries the url or the body`() {
        // §5.6. FetchFailure is an enum with no payload, so this is structural.
        FetchFailure.entries.forEach { it.name.contains("http") shouldBe false }
    }
}
