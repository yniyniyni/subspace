// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import art.yniyniyni.subspace.core.network.di.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
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
) {
    private val baseClient = OkHttpClient.Builder().build()

    /** Fetches [request]. Always on [Dispatchers.IO] (§5.3). */
    public suspend fun fetch(request: SubscriptionRequest): FetchOutcome =
        withContext(Dispatchers.IO) {
            val client = baseClient.newBuilder()
                .callTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .connectTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(request.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()

            val call = runCatching { client.newCall(request.toOkHttpRequest()) }.getOrNull()
                ?: return@withContext FetchOutcome.Failed(FetchFailure.NotFound)

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
 * The device-limit headers are checked **before** the status code: the panel can
 * report a reached limit on a 200, and reporting that as success would show the
 * user an empty server list with no explanation.
 */
private fun Response.toOutcome(headers: Map<String, String>): FetchOutcome {
    fun flag(name: String) = headers[name].equals(HEADER_TRUE, ignoreCase = true)

    return when {
        flag("x-hwid-max-devices-reached") || flag("x-hwid-limit") ->
            FetchOutcome.Failed(FetchFailure.DeviceLimitReached)

        code == HTTP_NOT_FOUND && flag("x-hwid-not-supported") ->
            FetchOutcome.Failed(FetchFailure.HwidRequired)

        code == HTTP_NOT_FOUND -> FetchOutcome.Failed(FetchFailure.NotFound)

        code >= HTTP_SERVER_ERROR_FLOOR -> FetchOutcome.Failed(FetchFailure.ServerError)

        code >= HTTP_CLIENT_ERROR_FLOOR -> FetchOutcome.Failed(FetchFailure.ClientError)

        else -> FetchOutcome.Success(body.string(), headers)
    }
}
