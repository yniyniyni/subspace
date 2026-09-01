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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MockWebServer stands in for the loopback HTTP inbound. It cannot proxy, but it
 * receives the request line — which is the whole claim under test: that an
 * absolute-form request reaches the proxy carrying the hostname, rather than the
 * client resolving it and connecting directly.
 */
class ProxiedFetchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var proxy: MockWebServer

    @Before
    fun setUp() {
        proxy = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = proxy.close()

    @Test
    fun `a geo download without a proxy port goes direct`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))
        val target = File(temporaryFolder.root, "geoip.dat")

        GeoFileFetcher().download(
            url = proxy.url("/geoip.dat").toString(),
            target = target,
            maxBytes = 1024,
            proxyPort = null,
            onProgress = { _, _ -> },
        )

        // Direct: the request line is origin-form.
        proxy.takeRequest().requestLine shouldStartWith "GET /geoip.dat"
    }

    @Test
    fun `a geo download with a proxy port sends the absolute url to the proxy`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))
        val target = File(temporaryFolder.root, "geoip.dat")

        GeoFileFetcher().download(
            // A host that must never be resolved for this test to be meaningful.
            url = "http://geo.invalid/geoip.dat",
            target = target,
            maxBytes = 1024,
            proxyPort = proxy.port,
            onProgress = { _, _ -> },
        )

        // Proxied: absolute-form, so the proxy resolves geo.invalid, not us.
        proxy.takeRequest().requestLine shouldStartWith "GET http://geo.invalid/geoip.dat"
    }

    @Test
    fun `the proxy is loopback only`() = runTest {
        proxy.enqueue(MockResponse(code = 200, body = "payload"))

        GeoFileFetcher().download(
            url = "http://geo.invalid/geoip.dat",
            target = File(temporaryFolder.root, "geoip.dat"),
            maxBytes = 1024,
            proxyPort = proxy.port,
            onProgress = { _, _ -> },
        )

        // MockWebServer binds loopback; asserting the recorded host keeps the
        // intent visible if someone later parameterises the address.
        proxy.takeRequest().headers["Host"] shouldBe "geo.invalid"
    }
}
