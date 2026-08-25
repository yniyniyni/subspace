// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.network

import art.yniyniyni.subspace.core.network.di.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val HTTP_NOT_FOUND = 404
private const val HTTP_SUCCESS_FLOOR = 200
private const val HTTP_SUCCESS_CEILING = 299
private const val HTTP_CLIENT_ERROR_FLOOR = 400
private const val HTTP_SERVER_ERROR_FLOOR = 500
private const val MAX_REDIRECTS = 5
private const val HEADER_TRUE = "true"

private const val HTTP_MULTIPLE_CHOICES = 300
private const val HTTP_MOVED_PERMANENTLY = 301
private const val HTTP_FOUND = 302
private const val HTTP_SEE_OTHER = 303
private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308

private val REDIRECT_STATUS_CODES = setOf(
    HTTP_MULTIPLE_CHOICES,
    HTTP_MOVED_PERMANENTLY,
    HTTP_FOUND,
    HTTP_SEE_OTHER,
    HTTP_TEMPORARY_REDIRECT,
    HTTP_PERMANENT_REDIRECT,
)

/** How long to wait before the single retry [SubscriptionFetcher.fetch] makes. */
private const val RETRY_DELAY_MILLIS = 400L

/**
 * Whether this outcome means "the connection died before the server said anything".
 *
 * The retry is for connections, not for answers. A 404, a 500 and an HWID marker are all replies —
 * asking again doubles the load on the provider and returns the same thing, and re-asking a panel
 * that just reported a device at its cap makes it re-count that device.
 *
 * [FetchFailure.TimedOut] is excluded for a different reason: the budget is already spent. A
 * second attempt would double the worst case the user waits through, for a server that has
 * already shown it will not answer in time. The scheduler's own backoff covers that case.
 *
 * [FetchFailure.NotFound] can arrive from a malformed URL rather than a response, and is excluded
 * with the rest of the answers — a URL that could not be parsed will not parse on a second pass.
 */
private fun FetchOutcome.isWorthRetrying(): Boolean =
    this is FetchOutcome.Failed &&
        (reason == FetchFailure.Unreachable || reason == FetchFailure.TlsFailure)

/**
 * Upper bound on a subscription response body, in bytes.
 *
 * A realistic subscription — a few hundred servers — is well under this even
 * in verbose formats: a single-line share-link format (vmess/vless/trojan/ss,
 * base64-encoded) runs roughly 150-300 bytes per server, so 500 servers is
 * under 150 KB; the verbosest realistic format, Clash YAML with full
 * per-proxy TLS/transport settings, runs closer to 1-2 KB per proxy, so 500
 * servers is still under 1 MB. 2 MiB leaves several times that headroom
 * while still bounding memory against a malicious or corrupted response —
 * the subscription URL is user-supplied and untrusted input (§A.1), not a
 * value this module has any reason to believe is well-behaved.
 */
internal const val MAX_SUBSCRIPTION_BODY_BYTES = 2L * 1024 * 1024

internal fun safeRedirectTarget(
    currentUrl: HttpUrl,
    location: String,
    redirectsFollowed: Int,
): HttpUrl? =
    if (redirectsFollowed < MAX_REDIRECTS) {
        currentUrl.resolve(location)?.takeIf { target ->
            currentUrl.isHttps &&
                target.isHttps &&
                target.scheme == currentUrl.scheme &&
                target.host == currentUrl.host &&
                target.port == currentUrl.port
        }
    } else {
        null
    }

/**
 * ARCHITECTURE.md §A.1's first pipeline stage.
 *
 * Returns a body and a header set. Deciding what they mean is `:core:parser`'s
 * job and persisting them is `:core:data`'s — §4 keeps this module to one
 * responsibility.
 *
 * Never throws: every network failure maps to a [FetchFailure]. §10.4 —
 * a swallowed failure that surfaces as a generic error is the outcome §A.4.1
 * singles out as the worst one.
 *
 * `deviceInfo` defaults to [EmptyDeviceInfo] so tests (and any other plain-JVM
 * caller) never have to touch `android.os.Build` to construct this class; the
 * default is inert for that purpose only — Hilt always supplies the real
 * [AndroidDeviceInfo] in production regardless of the Kotlin default, since the
 * generated `Factory` calls the constructor with every parameter explicit.
 */
@Singleton
public class SubscriptionFetcher
@Inject
constructor(
    private val hwidProvider: HwidProvider,
    @param:AppVersion private val appVersion: String,
    private val deviceInfo: DeviceInfo = EmptyDeviceInfo,
) : SubscriptionSource {
    private val baseClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Fetches [request], retrying once if the connection died before any answer arrived. Always on
     * [Dispatchers.IO] (§5.3).
     *
     * M4's device run turned up a reproducible habit: a refresh reports "could not reach the
     * server", and tapping refresh again immediately succeeds. Three things make that this
     * class's problem rather than the network's. The host resolves to a single `A` record with no
     * `AAAA`, and the device had no IPv6 route, so OkHttp has exactly **one** route to that
     * provider; OkHttp's own recovery works by advancing to the next route, which leaves a
     * connection-level failure on a single-route host terminal inside the call; and this method
     * made exactly one attempt. The user tapping again was performing, by hand, a retry the app
     * never attempted.
     *
     * What kills that first connection is not pinned down and does not need to be — a stale NAT
     * mapping on the gateway, a reset injected in the path, and Wi-Fi power-save all produce the
     * same shape, and one immediate retry answers all three. What is pinned down is the shape: the
     * device recorded `Unreachable`/`SocketException`, and a MockWebServer that closes the first
     * connection reproduces that pair exactly.
     *
     * Only [FetchOutcome.Failed.isWorthRetrying] failures get the second chance. A server that
     * answered — a 404, a 500, an HWID marker — has said something, and asking again would double
     * the load without changing the reply.
     */
    override suspend fun fetch(request: SubscriptionRequest): FetchOutcome =
        withContext(Dispatchers.IO) {
            val first = attempt(request)
            if (!first.isWorthRetrying()) return@withContext first

            // A brief pause rather than an instant re-dial: every mechanism above involves state
            // somewhere in the path that has to fall over before a fresh connection can replace
            // it, and hammering the same millisecond tends to reproduce the same failure.
            delay(RETRY_DELAY_MILLIS)
            attempt(request)
        }

    private fun attempt(request: SubscriptionRequest): FetchOutcome {
        // Client construction (the timeout chain) and call construction
        // (URL parsing) both throw on bad input rather than returning a
        // Result — confirmed empirically, not assumed: an out-of-range
        // duration throws IllegalStateException from OkHttp's own
        // duration check, and a malformed URL throws IllegalArgumentException
        // from Request.Builder.url(String). Sharing one runCatching over
        // both keeps the class's "never throws" guarantee absolute rather
        // than relying on the caller-side contract (§7 directive validation
        // keeps timeoutSeconds in 5-15) never being violated. Both failure
        // causes collapse onto NotFound: neither produced a request that
        // could reach a server, the same "we could not even form a call"
        // case a malformed URL already mapped to before this fix widened
        // the guard to cover client construction too.
        val call = runCatching {
            baseClient.newBuilder()
                .callTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceedWithSafeRedirects() }
                .apply {
                    // Proxy.Type.HTTP, never SOCKS. Whether a Java SOCKS proxy resolves the
                    // hostname locally before connecting — which would leak it to the local
                    // resolver while appearing to fetch through the tunnel (§5.2) — is not
                    // verified (research §8: "Not verified. Do not treat as fact.", §10.5). HTTP
                    // sidesteps the question rather than answering it: an HTTP proxy receives the
                    // hostname in absolute-form and resolves it at the far end by construction,
                    // so there is nothing to verify for this path regardless. A fresh client is
                    // already built per attempt (the timeout chain above is per-request), so
                    // there is no pool to share by caching one per port here.
                    request.proxyPort?.let { port ->
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                    }
                }
                .build()
                .newCall(request.toOkHttpRequest())
        }.getOrNull() ?: return FetchOutcome.Failed(FetchFailure.NotFound)

        return try {
            call.execute().use { response ->
                val headers = response.headers.names()
                    .associate { it.lowercase() to response.headers[it].orEmpty() }
                response.toOutcome(headers)
            }
        } catch (timeout: SocketTimeoutException) {
            FetchOutcome.Failed(FetchFailure.TimedOut, timeout.causeName())
        } catch (tls: SSLException) {
            FetchOutcome.Failed(FetchFailure.TlsFailure, tls.causeName())
        } catch (host: UnknownHostException) {
            FetchOutcome.Failed(FetchFailure.Unreachable, host.causeName())
        } catch (callTimeout: InterruptedIOException) {
            // OkHttp's callTimeout — the one that actually fires here — throws a plain
            // InterruptedIOException("timeout"), NOT SocketTimeoutException. All three
            // timeouts are set to the same duration and callTimeout spans the whole call,
            // so it wins the race, and without this branch every timeout fell through to
            // the generic IOException below and was reported as "could not reach the
            // server". M4's device run caught it against a deliberately hanging server:
            // the SocketTimeoutException branch above is real but almost never reached.
            FetchOutcome.Failed(FetchFailure.TimedOut, callTimeout.causeName())
        } catch (io: IOException) {
            // Deliberately last and deliberately broad-ish: the three above
            // are the cases worth naming to the user, and everything else is
            // "the network did not work". §10.4 wants a specific diagnosis
            // where one exists, not an invented one where it does not.
            FetchOutcome.Failed(FetchFailure.Unreachable, io.causeName())
        }
    }

    private fun SubscriptionRequest.toOkHttpRequest(): Request =
        Request.Builder()
            .url(url)
            .header("user-agent", userAgentOverride ?: UserAgent.default(appVersion))
            .header("x-device-os", "Android")
            .header("x-ver-os", deviceInfo.osVersion)
            .header("x-device-model", deviceInfo.model)
            .header("x-app-version", appVersion)
            .apply { if (hwidEnabled) header("x-hwid", hwidProvider.hwid()) }
            .get()
            .build()
}

/**
 * Maps status and headers to the §7 taxonomy.
 *
 * The HWID headers are checked **before** the status code, and independently of
 * it, because Remnawave does not report either HWID condition with an error
 * status. `checkHwidDeviceLimit` returns `isSubscriptionAllowed: false` and the
 * handler answers with an ordinary `SubscriptionWithConfigResponse` — a **200**
 * carrying `body: ''` (or a fallback-remarks template when `isShowCustomRemarks`
 * is on) plus the marker headers. Keying [FetchFailure.HwidRequired] off `404`
 * made it unreachable in production and reported a device-limited fetch as an
 * empty server list; M4's device run caught it. Verified against the panel
 * source, `subscription.service.ts` `checkHwidDeviceLimit`/`getSubscription`.
 *
 * `x-hwid-limit` is deliberately **not** consulted. Despite its name it is not a
 * "limit reached" signal: the panel emits it as a fixed v2rayTUN compatibility
 * marker whenever HWID enforcement is engaged (`headers['x-hwid-limit'] =
 * 'true'; // v2rayTUN`), and on one of its two response paths that assignment
 * sits outside the not-allowed branch entirely, so it rides along on successful
 * responses too. Treating it as a failure turned every fetch from such a panel
 * into a spurious [FetchFailure.DeviceLimitReached]. The two headers below are
 * the real signals, and `checkHwidDeviceLimit` makes them mutually exclusive.
 *
 * The success branch's read is capped at [MAX_SUBSCRIPTION_BODY_BYTES]. An
 * over-cap body maps to [FetchFailure.ServerError]: the taxonomy is closed
 * (§7), and of the existing members this is the closest fit — the server (or
 * whatever answered on its behalf) sent a response this client cannot safely
 * accept, the same category as any other response it cannot make sense of.
 * Not [FetchFailure.ClientError]: nothing about the outgoing request was
 * wrong.
 */
private fun Response.toOutcome(headers: Map<String, String>): FetchOutcome {
    fun flag(name: String) = headers[name].equals(HEADER_TRUE, ignoreCase = true)

    return when {
        flag("x-hwid-max-devices-reached") ->
            FetchOutcome.Failed(FetchFailure.DeviceLimitReached)

        flag("x-hwid-not-supported") ->
            FetchOutcome.Failed(FetchFailure.HwidRequired)

        code == HTTP_NOT_FOUND -> FetchOutcome.Failed(FetchFailure.NotFound)

        code >= HTTP_SERVER_ERROR_FLOOR -> FetchOutcome.Failed(FetchFailure.ServerError)

        code >= HTTP_CLIENT_ERROR_FLOOR -> FetchOutcome.Failed(FetchFailure.ClientError)

        code in HTTP_SUCCESS_FLOOR..HTTP_SUCCESS_CEILING -> body.readBounded(MAX_SUBSCRIPTION_BODY_BYTES)
            ?.let { FetchOutcome.Success(it, headers) }
            ?: FetchOutcome.Failed(FetchFailure.ServerError)

        else -> FetchOutcome.Failed(FetchFailure.ServerError)
    }
}

private fun Int.isRedirectStatus(): Boolean = this in REDIRECT_STATUS_CODES

private fun Interceptor.Chain.proceedWithSafeRedirects(): Response {
    var currentRequest = request()
    var redirectsFollowed = 0
    var response = proceed(currentRequest)

    while (response.code.isRedirectStatus() && !response.hasHwidFailureMarker()) {
        val target = response.header("location")?.let { location ->
            safeRedirectTarget(
                currentUrl = response.request.url,
                location = location,
                redirectsFollowed = redirectsFollowed,
            )
        } ?: break

        response.close()
        // Reusing the request retains subscription headers. This is safe only after the target
        // has passed the HTTPS same-origin check above; no rejected target ever reaches proceed.
        currentRequest = currentRequest.newBuilder().url(target).build()
        redirectsFollowed++
        response = proceed(currentRequest)
    }

    return response
}

private fun Response.hasHwidFailureMarker(): Boolean =
    header("x-hwid-max-devices-reached").equals(HEADER_TRUE, ignoreCase = true) ||
        header("x-hwid-not-supported").equals(HEADER_TRUE, ignoreCase = true)

/**
 * This throwable's class name, plus its root cause's when that differs — never any message.
 *
 * See [FetchOutcome.Failed.detail] for why the message is excluded and why the class name alone is
 * safe under §5.6. The root cause is included because OkHttp wraps: the interesting half of a
 * failed handshake is usually the `EOFException`/`ConnectException` underneath a generic
 * `SSLException`, and "SSLException" on its own would leave this field almost as uninformative as
 * the taxonomy member it accompanies.
 *
 * The cause chain is walked with a bounded loop rather than recursion — a self-referencing or
 * cyclic `cause` is rare but constructible, and this runs on every network failure.
 */
private fun Throwable.causeName(): String {
    var root: Throwable = this
    var hops = 0
    while (hops < MAX_CAUSE_HOPS) {
        root = root.cause ?: break
        hops++
    }
    val outer = javaClass.simpleName
    val inner = root.javaClass.simpleName
    return if (root === this || inner == outer) outer else "$outer<-$inner"
}

private const val MAX_CAUSE_HOPS = 8

/**
 * Reads the body as text, or `null` if it exceeds [maxBytes].
 *
 * Bounds the actual bytes pulled off the stream, not the `Content-Length`
 * header: the header is attacker-controlled (or simply absent) exactly like
 * the rest of the response, so trusting it is not a guard. [okio.BufferedSource.request]
 * buffers up to `maxBytes + 1` bytes directly from the socket and stops —
 * it never reads further than that regardless of how much data the server
 * keeps sending — so memory use is bounded whether or not the declared
 * length is honest.
 */
private fun ResponseBody.readBounded(maxBytes: Long): String? =
    if (source().request(maxBytes + 1)) null else string()
