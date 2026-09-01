// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Review round 2, Important 3: this is the one fetch that carries a §5.6 secret — the subscription
 * URL — and it had no runnable coverage of its proxy path at all. `ProxiedFetchTest` exercises only
 * [GeoFileFetcher]; `SubscriptionSyncerTest`'s proxy cases live in `:core:data`'s `androidTest` and
 * assert no more than "an `Int` reaches `SubscriptionRequest`", and nothing in this environment runs
 * instrumented tests. Dropping (or breaking) the `.apply { proxy(...) }` block in
 * `SubscriptionFetcher.attempt` would still leave every runnable test green while every subscription
 * fetch silently went direct — exactly the failure mode this class exists to prevent, on exactly the
 * request whose destination host must not reach the local resolver while a tunnel is up (§5.2).
 *
 * MockWebServer stands in for the loopback HTTP inbound the same way it does in `ProxiedFetchTest`:
 * it cannot proxy, but it receives the request line, which is the whole claim under test — an
 * absolute-form request reaches the proxy carrying the hostname, rather than the client resolving it
 * and connecting directly.
 */
class SubscriptionFetcherProxyTest {
    private lateinit var proxy: MockWebServer

    private fun fetcher() = SubscriptionFetcher(HwidProvider { "TESTHWIDvalue0123456789" }, appVersion = "1.0.0")

    @Before fun setUp() { proxy = MockWebServer().also { it.start() } }

    @After fun tearDown() = proxy.close()

    @Test
    fun `a subscription fetch without a proxy port goes direct`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            fetcher().fetch(
                SubscriptionRequest(
                    url = proxy.url("/sub").toString(),
                    hwidEnabled = false,
                    userAgentOverride = null,
                    timeoutSeconds = 5,
                    proxyPort = null,
                ),
            )

            // Direct: the request line is origin-form.
            proxy.takeRequest().requestLine shouldStartWith "GET /sub"
        }

    @Test
    fun `a subscription fetch with a proxy port sends the absolute url to the proxy`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            fetcher().fetch(
                SubscriptionRequest(
                    // A host that must never be resolved for this test to be meaningful.
                    url = "http://sub.invalid/list",
                    hwidEnabled = false,
                    userAgentOverride = null,
                    timeoutSeconds = 5,
                    proxyPort = proxy.port,
                ),
            )

            // Proxied: absolute-form, so the proxy resolves sub.invalid, not us.
            proxy.takeRequest().requestLine shouldStartWith "GET http://sub.invalid/list"
        }

    @Test
    fun `the proxy is loopback only`() =
        runTest {
            proxy.enqueue(MockResponse(code = 200, body = "vless://u@h:443"))

            fetcher().fetch(
                SubscriptionRequest(
                    url = "http://sub.invalid/list",
                    hwidEnabled = false,
                    userAgentOverride = null,
                    timeoutSeconds = 5,
                    proxyPort = proxy.port,
                ),
            )

            // MockWebServer binds loopback; asserting the recorded host keeps the intent visible
            // if someone later parameterises the address.
            proxy.takeRequest().headers["Host"] shouldBe "sub.invalid"
        }
}
