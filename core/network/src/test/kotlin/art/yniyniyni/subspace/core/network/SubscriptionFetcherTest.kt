// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
    fun `a 200 with x-hwid-not-supported is HwidRequired`() = runTest {
        // The milestone's exit criterion, in the shape the panel actually sends.
        // M4's device run established that Remnawave does NOT 404 a missing
        // HWID: checkHwidDeviceLimit returns isSubscriptionAllowed = false and
        // the handler answers with an ordinary SubscriptionWithConfigResponse —
        // 200, empty body, marker headers. Keying this off 404 (as this file
        // originally did) made HwidRequired unreachable in production.
        //
        // x-hwid-limit rides along because the panel sets it whenever HWID
        // enforcement is engaged; including it here pins the precedence that
        // regressed the exit criterion into DeviceLimitReached.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("x-hwid-active", "true")
                .addHeader("x-hwid-not-supported", "true")
                .addHeader("x-hwid-limit", "true")
                .build(),
        )

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.HwidRequired)
    }

    @Test
    fun `x-hwid-not-supported on a 404 is still HwidRequired`() = runTest {
        // The check is status-independent, so a panel that does answer 404 (or
        // a proxy that rewrites the status) still reaches the right message.
        server.enqueue(
            MockResponse.Builder()
                .code(404)
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
                .addHeader("x-hwid-limit", "true")
                .build(),
        )

        fetcher().fetch(request()) shouldBe
            FetchOutcome.Failed(FetchFailure.DeviceLimitReached)
    }

    @Test
    fun `x-hwid-limit alone is not a failure`() = runTest {
        // x-hwid-limit is a fixed v2rayTUN compatibility marker, not a
        // limit-reached signal. On one of the panel's two response paths the
        // assignment sits outside the not-allowed branch, so it ships on
        // successful responses; treating it as a failure turned every fetch
        // from such a panel into a spurious DeviceLimitReached.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("x-hwid-limit", "true")
                .body("vless://one\nvless://two")
                .build(),
        )

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Success>()
        outcome.body shouldBe "vless://one\nvless://two"
        outcome.headers["x-hwid-limit"] shouldBe "true"
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

    @Test
    fun `an out-of-contract timeout does not throw`() = runTest {
        // The directive registry keeps timeoutSeconds in 5-15, but the class's
        // own KDoc claims an unconditional "never throws" — OkHttpClient's
        // timeout builder methods throw IllegalArgumentException on a negative
        // value, and that construction has to be covered by the same guard as
        // request construction, not left to the caller-side contract holding.
        fetcher().fetch(request(timeoutSeconds = -1)) shouldBe
            FetchOutcome.Failed(FetchFailure.NotFound)
    }

    @Test
    fun `a response over the size cap is rejected rather than read into memory`() = runTest {
        // The subscription URL is untrusted input (§A.1): a hostile or
        // corrupted server could otherwise OOM the fetch. The taxonomy is
        // closed (§7), so this maps onto ServerError — see toOutcome's KDoc
        // for why that member and not ClientError.
        val oversized = "a".repeat((MAX_SUBSCRIPTION_BODY_BYTES + 1).toInt())
        server.enqueue(MockResponse(code = 200, body = oversized))

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.ServerError)
    }
}
