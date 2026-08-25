// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.network

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class GeoFileFetcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val fetcher = GeoFileFetcher()

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.close()

    private fun target(): File = File(temporaryFolder.root, "geoip.dat")

    @Test
    fun writesTheBodyToDiskAndReportsItsSizeAndDigest() = runTest {
        server.enqueue(MockResponse(code = 200, body = "hello"))
        val file = target()

        val outcome = fetcher.download(server.url("/geoip.dat").toString(), file, MAX) { _, _ -> }

        outcome.shouldBeInstanceOf<GeoDownloadOutcome.Success>()
        outcome.bytes shouldBe 5L
        // SHA-256 of "hello".
        outcome.sha256 shouldBe "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        file.readText() shouldBe "hello"
    }

    @Test
    fun reportsProgressAsBytesArrive() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(4096))).build())
        val seen = mutableListOf<Long>()

        fetcher.download(server.url("/geoip.dat").toString(), target(), MAX) { bytes, _ -> seen += bytes }

        seen.isNotEmpty() shouldBe true
        seen.last() shouldBe 4096L
    }

    // The row renders "12 MB / 23 MB", so the total has to come from the same
    // callback as the count. Without it the UI can only show a spinner.
    @Test
    fun reportsTheDeclaredTotalAlongsideTheRunningCount() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(4096))).build())
        val seen = mutableListOf<Pair<Long, Long?>>()

        fetcher.download(server.url("/geoip.dat").toString(), target(), MAX) { bytes, total ->
            seen += bytes to total
        }

        seen.last() shouldBe (4096L to 4096L)
    }

    // A chunked response declares no length. Reporting a made-up total would
    // render as "12 MB / 0 MB"; null is what lets the row show an
    // indeterminate bar instead.
    @Test
    fun aResponseWithoutAContentLengthReportsANullTotal() = runTest {
        server.enqueue(
            MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(4096)), 1024).build(),
        )
        val seen = mutableListOf<Pair<Long, Long?>>()

        fetcher.download(server.url("/geoip.dat").toString(), target(), MAX) { bytes, total ->
            seen += bytes to total
        }

        seen.last().second shouldBe null
    }

    @Test
    fun propagatesAThrowingProgressCallbackAndDeletesThePartialFile() = runTest {
        server.enqueue(MockResponse(code = 200, body = "hello"))
        val file = target()

        val failure =
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                try {
                    withTimeout(5.seconds) {
                        fetcher.download(server.url("/geoip.dat").toString(), file, MAX) { _, _ ->
                            throw IllegalStateException("progress failed")
                        }
                    }
                    null
                } catch (error: Throwable) {
                    error
                }
            }

        failure.shouldBeInstanceOf<IllegalStateException>()
        failure.message shouldBe "progress failed"
        file.exists() shouldBe false
    }

    // A source that lies about its size, or a hijacked URL serving an endless
    // stream, must not fill the user's storage.
    @Test
    fun abortsAndDeletesThePartialFileWhenTheBodyExceedsTheCap() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(4096))).build())
        val file = target()

        val outcome = fetcher.download(server.url("/geoip.dat").toString(), file, 1024L) { _, _ -> }

        outcome shouldBe GeoDownloadOutcome.Failed(GeoFetchFailure.TooLarge)
        file.exists() shouldBe false
    }

    @Test
    fun mapsA404ToNotFound() = runTest {
        server.enqueue(MockResponse(code = 404))

        fetcher.download(server.url("/nope.dat").toString(), target(), MAX) { _, _ -> } shouldBe
            GeoDownloadOutcome.Failed(GeoFetchFailure.NotFound)
    }

    @Test
    fun mapsA500ToServerError() = runTest {
        server.enqueue(MockResponse(code = 500))

        fetcher.download(server.url("/geoip.dat").toString(), target(), MAX) { _, _ -> } shouldBe
            GeoDownloadOutcome.Failed(GeoFetchFailure.ServerError)
    }

    // Every curated source is a `releases/latest/download/…` URL, and GitHub
    // answers those with a 302 chain to its asset host. A fetcher that stops at
    // the first response downloads nothing from the catalogue at all.
    @Test
    fun followsARedirectToTheAssetHost() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .code(302)
                .addHeader("Location", server.url("/assets/geoip.dat").toString())
                .build(),
        )
        server.enqueue(MockResponse(code = 200, body = "hello"))
        val file = target()

        val outcome = fetcher.download(server.url("/geoip.dat").toString(), file, MAX) { _, _ -> }

        outcome.shouldBeInstanceOf<GeoDownloadOutcome.Success>()
        outcome.bytes shouldBe 5L
        file.readText() shouldBe "hello"
        server.requestCount shouldBe 2
    }

    @Test
    fun mapsADeadConnectionToUnreachableAndLeavesNoPartialFile() = runTest {
        val url = server.url("/geoip.dat").toString()
        server.close()
        val file = target()

        val outcome = fetcher.download(url, file, MAX) { _, _ -> }

        outcome.shouldBeInstanceOf<GeoDownloadOutcome.Failed>()
        outcome.reason shouldBe GeoFetchFailure.Unreachable
        file.exists() shouldBe false
    }

    @Test
    fun mapsAMidBodyDisconnectToUnreachableAndDeletesThePartialFile() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .body("partial")
                .onResponseBody(SocketEffect.CloseSocket())
                .build(),
        )
        val file = target()

        val outcome = fetcher.download(server.url("/geoip.dat").toString(), file, MAX) { _, _ -> }

        outcome.shouldBeInstanceOf<GeoDownloadOutcome.Failed>()
        outcome.reason shouldBe GeoFetchFailure.Unreachable
        file.exists() shouldBe false
    }

    @Test
    fun mapsAStalledBodyReadToTimedOutAndDeletesThePartialFile() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .body("stalled")
                .bodyDelay(5, TimeUnit.SECONDS)
                .build(),
        )
        val file = target()
        val shortReadTimeoutFetcher =
            GeoFileFetcher.withClient(
                OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build(),
            )

        val outcome = shortReadTimeoutFetcher.download(server.url("/geoip.dat").toString(), file, MAX) { _, _ -> }

        outcome.shouldBeInstanceOf<GeoDownloadOutcome.Failed>()
        outcome.reason shouldBe GeoFetchFailure.TimedOut
        file.exists() shouldBe false
    }

    @Test
    fun cancellationCancelsAStalledReadAndDeletesThePartialFile() = runTest {
        server.enqueue(
            MockResponse
                .Builder()
                .body("stalled")
                .bodyDelay(30, TimeUnit.SECONDS)
                .build(),
        )
        val file = target()
        file.writeText("partial")
        val download =
            async(Dispatchers.IO) {
                fetcher.download(server.url("/geoip.dat").toString(), file, MAX) { _, _ -> }
            }

        server.takeRequest(5, TimeUnit.SECONDS).shouldNotBeNull()
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) { download.cancelAndJoin() }
        }

        file.exists() shouldBe false
    }

    @Test
    fun rejectsAUrlThatIsNotHttp() = runTest {
        fetcher.download("file:///etc/passwd", target(), MAX) { _, _ -> } shouldBe
            GeoDownloadOutcome.Failed(GeoFetchFailure.InvalidUrl)
    }

    private companion object {
        const val MAX = 100L * 1024 * 1024
    }
}
