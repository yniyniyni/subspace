// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import art.yniyniyni.subspace.core.network.di.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val HTTP_NOT_FOUND = 404
private const val HTTP_CLIENT_ERROR_FLOOR = 400
private const val HTTP_SERVER_ERROR_FLOOR = 500
private const val HEADER_TRUE = "true"

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
    private val baseClient = OkHttpClient.Builder().build()

    /** Fetches [request]. Always on [Dispatchers.IO] (§5.3). */
    override suspend fun fetch(request: SubscriptionRequest): FetchOutcome =
        withContext(Dispatchers.IO) {
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
                    .build()
                    .newCall(request.toOkHttpRequest())
            }.getOrNull() ?: return@withContext FetchOutcome.Failed(FetchFailure.NotFound)

            try {
                call.execute().use { response ->
                    val headers = response.headers.names()
                        .associate { it.lowercase() to response.headers[it].orEmpty() }
                    response.toOutcome(headers)
                }
            } catch (ignoredTimeout: SocketTimeoutException) {
                FetchOutcome.Failed(FetchFailure.TimedOut)
            } catch (ignoredTls: SSLException) {
                FetchOutcome.Failed(FetchFailure.TlsFailure)
            } catch (ignoredHost: UnknownHostException) {
                FetchOutcome.Failed(FetchFailure.Unreachable)
            } catch (ignoredCallTimeout: InterruptedIOException) {
                // OkHttp's callTimeout — the one that actually fires here — throws a plain
                // InterruptedIOException("timeout"), NOT SocketTimeoutException. All three
                // timeouts are set to the same duration and callTimeout spans the whole call,
                // so it wins the race, and without this branch every timeout fell through to
                // the generic IOException below and was reported as "could not reach the
                // server". M4's device run caught it against a deliberately hanging server:
                // the SocketTimeoutException branch above is real but almost never reached.
                FetchOutcome.Failed(FetchFailure.TimedOut)
            } catch (ignoredIo: IOException) {
                // Deliberately last and deliberately broad-ish: the three above
                // are the cases worth naming to the user, and everything else is
                // "the network did not work". §10.4 wants a specific diagnosis
                // where one exists, not an invented one where it does not.
                FetchOutcome.Failed(FetchFailure.Unreachable)
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

        else -> body.readBounded(MAX_SUBSCRIPTION_BODY_BYTES)
            ?.let { FetchOutcome.Success(it, headers) }
            ?: FetchOutcome.Failed(FetchFailure.ServerError)
    }
}

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
