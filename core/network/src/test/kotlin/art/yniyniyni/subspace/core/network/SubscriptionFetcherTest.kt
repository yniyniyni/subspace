// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

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
    fun `a server that never answers is TimedOut, not Unreachable`() = runTest {
        // M4's device run, against a deliberately hanging server. OkHttp's callTimeout — the one
        // that actually fires, since all three timeouts share a duration and callTimeout spans
        // the whole call — throws a plain InterruptedIOException, not SocketTimeoutException, so
        // every timeout used to land in the generic IOException branch and be reported as
        // "could not reach the server".
        server.enqueue(MockResponse.Builder().headersDelay(30, TimeUnit.SECONDS).build())

        val outcome = fetcher().fetch(request().copy(timeoutSeconds = 1))

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.TimedOut
        // The detail is what tells the two timeout paths apart in a log. Which one fires is a
        // genuine race — callTimeout and readTimeout are set to the same duration, so this
        // alternates between runs between `SocketTimeoutException` and
        // `InterruptedIOException<-SocketException` — and asserting either exact string would be
        // a flaky test. What must hold is that a timeout names a timeout, and that the branch
        // taken is recorded at all.
        outcome.detail.shouldNotBeNull().shouldContain("Timeout|Interrupted".toRegex())
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
    fun `300 without a location is a server failure`() = runTest {
        server.enqueue(MockResponse(code = 300))

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.ServerError)
    }

    @Test
    fun `304 is a server failure rather than a successful empty subscription`() = runTest {
        server.enqueue(MockResponse(code = 304))

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.ServerError)
    }

    @Test
    fun `a rejected cross-origin redirect sends no subscription headers to the target`() = runTest {
        MockWebServer().use { otherOrigin ->
            otherOrigin.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(302)
                    .addHeader("Location", otherOrigin.url("/redirected"))
                    .build(),
            )
            otherOrigin.enqueue(MockResponse(code = 200, body = "must-not-be-fetched"))

            val outcome = fetcher().fetch(
                request().copy(userAgentOverride = "ProviderSpecific/secret"),
            )

            outcome shouldBe FetchOutcome.Failed(FetchFailure.ServerError)
            server.requestCount shouldBe 1
            // No second request means neither x-hwid nor the provider-specific User-Agent can be
            // forwarded to the redirect target.
            otherOrigin.requestCount shouldBe 0
        }
    }

    @Test
    fun `a same-origin relative redirect from an insecure source sends no second request`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "/redirected")
                .build(),
        )
        server.enqueue(MockResponse(code = 200, body = "must-not-be-fetched"))

        fetcher().fetch(request()) shouldBe FetchOutcome.Failed(FetchFailure.ServerError)
        server.requestCount shouldBe 1
    }

    @Test
    fun `redirect decision allows a relative target on the same HTTPS origin`() {
        safeRedirectTarget(
            currentUrl = "https://subscriptions.example/base/list".toHttpUrl(),
            location = "../next?format=vless",
            redirectsFollowed = 0,
        ) shouldBe "https://subscriptions.example/next?format=vless".toHttpUrl()
    }

    @Test
    fun `redirect decision treats an explicit default port as the same HTTPS origin`() {
        safeRedirectTarget(
            currentUrl = "https://subscriptions.example/list".toHttpUrl(),
            location = "https://subscriptions.example:443/next",
            redirectsFollowed = 0,
        ) shouldBe "https://subscriptions.example/next".toHttpUrl()
    }

    @Test
    fun `redirect decision rejects HTTPS downgrade`() {
        safeRedirectTarget(
            currentUrl = "https://subscriptions.example/list".toHttpUrl(),
            location = "http://subscriptions.example/next",
            redirectsFollowed = 0,
        ) shouldBe null
    }

    @Test
    fun `redirect decision rejects a different host`() {
        safeRedirectTarget(
            currentUrl = "https://subscriptions.example/list".toHttpUrl(),
            location = "https://redirect.example/next",
            redirectsFollowed = 0,
        ) shouldBe null
    }

    @Test
    fun `redirect decision rejects a different effective port`() {
        safeRedirectTarget(
            currentUrl = "https://subscriptions.example/list".toHttpUrl(),
            location = "https://subscriptions.example:444/next",
            redirectsFollowed = 0,
        ) shouldBe null
    }

    @Test
    fun `redirect decision rejects a relative target from an insecure origin`() {
        safeRedirectTarget(
            currentUrl = "http://subscriptions.example/list".toHttpUrl(),
            location = "/next",
            redirectsFollowed = 0,
        ) shouldBe null
    }

    @Test
    fun `redirect decision allows five hops and rejects a sixth`() {
        val current = "https://subscriptions.example/list".toHttpUrl()

        safeRedirectTarget(current, "/fifth", redirectsFollowed = 4) shouldBe
            "https://subscriptions.example/fifth".toHttpUrl()
        safeRedirectTarget(current, "/sixth", redirectsFollowed = 5) shouldBe null
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

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.Unreachable
        outcome.detail shouldBe "ConnectException"
    }

    @Test
    fun `a failure never carries the url, the host or the body`() = runTest {
        // This test used to assert `FetchFailure.entries.forEach { it.name.contains("http") ==
        // false }` — enum *constant names*, which cannot fail short of someone declaring a member
        // called `httpSomething`. Its comment justified that with "FetchFailure is an enum with no
        // payload, so this is structural", and that premise stopped being true when
        // FetchOutcome.Failed gained a free-text `detail`. The invariant worth guarding is that
        // the payload which now exists cannot carry any of it.
        val host = "leak-canary-9f3a1c.example"
        server.enqueue(MockResponse(code = 500, body = "vless://11111111-1111-1111-1111-111111111111@$host:443"))

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        val rendered = outcome.toString() + outcome.detail.orEmpty()
        rendered shouldNotContain host
        rendered shouldNotContain "vless://"
        rendered shouldNotContain server.url("/sub").toString()
    }

    @Test
    fun `a failure detail names the exception and never quotes its message`() = runTest {
        // §5.6, and the reason FetchOutcome.Failed.detail carries a class name rather than the
        // message: DNS and TLS exception messages routinely embed the host, and a subscription
        // URL's host is a secret. This host does not resolve, so the message would contain it.
        val host = "no-such-host-b7f2a1.invalid"
        val outcome = fetcher().fetch(
            SubscriptionRequest(
                url = "https://$host/sub",
                hwidEnabled = true,
                userAgentOverride = null,
                timeoutSeconds = 5,
            ),
        )

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.Unreachable
        outcome.detail shouldBe "UnknownHostException"
        outcome.detail?.contains(host) shouldBe false
    }

    @Test
    fun `a response-derived failure has no exception to name`() = runTest {
        // Nothing was thrown, so there is no cause beyond the status itself; the syncer falls
        // back to the reason's own name rather than inventing one.
        server.enqueue(MockResponse(code = 500))

        val outcome = fetcher().fetch(request())

        outcome.shouldBeInstanceOf<FetchOutcome.Failed>()
        outcome.reason shouldBe FetchFailure.ServerError
        outcome.detail shouldBe null
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
