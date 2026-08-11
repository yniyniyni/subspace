// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ImportOrdering") // Detekt's custom layout conflicts with the project-wide Kotlin import order.

package art.yniyniyni.subspace.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val HTTP_NOT_FOUND = 404
private const val HTTP_SERVER_ERROR_FLOOR = 500

/** 64 KiB: large enough that a 23 MB file is not a million syscalls, small enough to stay off the heap. */
private const val BUFFER_BYTES = 64 * 1024

/** A geo database is a big file over a possibly-slow link; the subscription timeout would be far too short. */
private const val CALL_TIMEOUT_MINUTES = 10L
private const val CONNECT_TIMEOUT_SECONDS = 30L

private const val HEX_MASK = 0xff

/** Carries a caller callback failure across OkHttp's asynchronous response boundary. */
private class ProgressCallbackException(cause: Throwable) : RuntimeException(cause)

/**
 * Why a geo file did not arrive.
 *
 * A **separate** vocabulary from [FetchFailure], not a reuse. That one is shared
 * with the subscription pipeline and closed on purpose; adding `TooLarge` and
 * `InvalidUrl` to it would widen exhaustive `when`s across `:core:data` for two
 * cases that only exist here.
 *
 * §5.6: no member carries a URL, a path, or a body.
 */
public enum class GeoFetchFailure {
    InvalidUrl,
    NotFound,
    Unreachable,
    TimedOut,
    ClientError,
    ServerError,

    /** The body exceeded the caller's cap. The partial file is deleted. */
    TooLarge,

    /** The bytes arrived but could not be written to disk. */
    WriteFailed,
}

/** The result of one [GeoFileFetcher.download]. */
public sealed interface GeoDownloadOutcome {
    /** @property sha256 lower-case hex, recorded so a re-download can be recognised as identical. */
    public data class Success(val bytes: Long, val sha256: String) : GeoDownloadOutcome

    public data class Failed(
        val reason: GeoFetchFailure,
        val detail: String? = null,
    ) : GeoDownloadOutcome
}

/**
 * Streams a geo database to a file.
 *
 * **Never buffers the body.** The measured sizes run from 228 KB to 73.7 MB
 * (research §7) and a `ByteArray` of the large one is an OOM on a mid-range
 * device — which is why this does not follow [SubscriptionFetcher]'s shape, where
 * reading a bounded body into a `String` is correct.
 *
 * Network and disk failures map to a [GeoFetchFailure] (§10.4). An exception
 * thrown by [onProgress][download] is propagated after the partial [target] is
 * deleted.
 *
 * The caller supplies [target] in a **staging** directory. A failed or oversized
 * download deletes it, so a partial file can never be mistaken for an installed
 * one.
 */
@Singleton
public class GeoFileFetcher private constructor(
    private val client: OkHttpClient,
) {
    @Inject
    public constructor() :
        this(
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

    /** Allows JVM tests to exercise response-read failures with a short read timeout. */
    internal companion object {
        internal fun withClient(client: OkHttpClient): GeoFileFetcher = GeoFileFetcher(client)
    }

    /**
     * Downloads [url] into [target], calling [onProgress] with the running byte
     * count. Always on [Dispatchers.IO] (§5.3).
     *
     * @param maxBytes hard ceiling; exceeding it aborts and deletes [target].
     * @param proxyPort the tunnel's loopback HTTP proxy, or null to fetch
     *   directly. Declared here rather than added later because inserting a
     *   parameter before the trailing lambda would break every call site.
     *   Wired in Part 2, Task 13; until then every caller passes null.
     */
    @Suppress(
        "ReturnCount", // §10.4: each failure is distinct and returns where it is detected.
        "SwallowedException", // Invalid URLs intentionally have no implementation-detail field.
        "UnusedParameter", // proxyPort is part of the stable API and is wired in Part 2, Task 13.
    )
    public suspend fun download(
        url: String,
        target: File,
        maxBytes: Long,
        proxyPort: Int? = null,
        onProgress: (Long) -> Unit,
    ): GeoDownloadOutcome =
        withContext(Dispatchers.IO) {
            val request =
                try {
                    Request.Builder().url(url).build()
                } catch (_: IllegalArgumentException) {
                    // Malformed URLs are rejected before a request can be made.
                    return@withContext GeoDownloadOutcome.Failed(GeoFetchFailure.InvalidUrl)
                }
            // OkHttp accepts a few non-network schemes at request construction;
            // reject them before a custom source can reach local content.
            if (request.url.scheme != "http" && request.url.scheme != "https") {
                return@withContext GeoDownloadOutcome.Failed(GeoFetchFailure.InvalidUrl)
            }

            var succeeded = false
            try {
                val outcome =
                    awaitOutcome(
                        call = client.newCall(request),
                        target = target,
                        maxBytes = maxBytes,
                        onProgress = onProgress,
                    )
                succeeded = outcome is GeoDownloadOutcome.Success
                outcome
            } finally {
                if (!succeeded) {
                    target.delete()
                }
            }
        }

    @Suppress(
        "NestedBlockDepth", // The resource scope and streaming loop must remain adjacent for safety.
        "ReturnCount", // TooLarge and WriteFailed must stop and delete the partial target immediately.
    )
    private suspend fun stream(
        source: java.io.InputStream,
        target: File,
        maxBytes: Long,
        onProgress: (Long) -> Unit,
    ): GeoDownloadOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)

        try {
            target.parentFile?.mkdirs()
            target.outputStream().use { sink ->
                while (true) {
                    // A 73 MB download must stop promptly when the user cancels
                    // or the worker is stopped; the read loop has no other
                    // suspension point to be cancelled at.
                    coroutineContext.ensureActive()

                    val read =
                        try {
                            source.read(buffer)
                        } catch (error: IOException) {
                            return networkFailure(error)
                        }
                    if (read < 0) break

                    total += read
                    if (total > maxBytes) {
                        return GeoDownloadOutcome.Failed(GeoFetchFailure.TooLarge)
                    }

                    sink.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    reportProgress(onProgress, total)
                }
                sink.flush()
            }
        } catch (error: IOException) {
            return GeoDownloadOutcome.Failed(GeoFetchFailure.WriteFailed, error.javaClass.simpleName)
        }

        return GeoDownloadOutcome.Success(total, digest.digest().toHex())
    }

    @Suppress("TooGenericExceptionCaught") // Callers may throw any Throwable from their callback.
    private fun reportProgress(
        onProgress: (Long) -> Unit,
        total: Long,
    ) {
        try {
            onProgress(total)
        } catch (error: Throwable) {
            throw ProgressCallbackException(error)
        }
    }

    private suspend fun awaitOutcome(
        call: Call,
        target: File,
        maxBytes: Long,
        onProgress: (Long) -> Unit,
    ): GeoDownloadOutcome =
        suspendCancellableCoroutine { continuation ->
            val responseJob = AtomicReference<Job?>(null)
            continuation.invokeOnCancellation {
                call.cancel()
                responseJob.getAndSet(null)?.cancel()
                target.delete()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(networkFailure(e))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        val job =
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val outcome = response.use { successfulResponse ->
                                        if (!successfulResponse.isSuccessful) {
                                            GeoDownloadOutcome.Failed(statusFailure(successfulResponse.code))
                                        } else {
                                            stream(
                                                successfulResponse.body.byteStream(),
                                                target,
                                                maxBytes,
                                                onProgress,
                                            )
                                        }
                                    }
                                    if (continuation.isActive) {
                                        continuation.resume(outcome)
                                    }
                                } catch (error: IOException) {
                                    if (continuation.isActive) {
                                        continuation.resume(networkFailure(error))
                                    }
                                } catch (error: ProgressCallbackException) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(error.cause ?: error)
                                    }
                                }
                            }
                        responseJob.set(job)
                        if (!continuation.isActive) {
                            job.cancel()
                        }
                    }
                },
            )
        }

    private fun networkFailure(error: IOException): GeoDownloadOutcome.Failed {
        val reason =
            when (error) {
                is java.net.SocketTimeoutException,
                is java.io.InterruptedIOException,
                -> GeoFetchFailure.TimedOut

                else -> GeoFetchFailure.Unreachable
            }
        return GeoDownloadOutcome.Failed(reason, error.javaClass.simpleName)
    }

    private fun statusFailure(code: Int): GeoFetchFailure =
        when {
            code == HTTP_NOT_FOUND -> GeoFetchFailure.NotFound
            code >= HTTP_SERVER_ERROR_FLOOR -> GeoFetchFailure.ServerError
            else -> GeoFetchFailure.ClientError
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and HEX_MASK) }
}
