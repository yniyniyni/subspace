// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.TagTraffic
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "MetricsClient"

/**
 * Parses xray's `/debug/vars` payload.
 *
 * Shape from `app/metrics/metrics.go:175-201` (xray-core v26.7.11): counter
 * names are split on `>>>` and bucketed into `inbound`/`outbound`/`user`, so the
 * key under `outbound` is the outbound tag itself.
 *
 * Only the `outbound` bucket is read. Inbound counters measure the loopback
 * SOCKS inbound — which is the same traffic counted a second time, one hop
 * earlier — and rendering both would double a total the user reads as one.
 *
 * Never throws: a malformed or absent payload yields no rows. This is a
 * display-only feature, and ARCHITECTURE.md §10.4's "fail loudly" applies to
 * the start sequence, not to a counter that can simply not render.
 */
internal fun parseMetricsPayload(json: String): List<TagTraffic> =
    try {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val stats = root["stats"] as? JsonObject ?: return emptyList()
        val outbound = stats["outbound"] as? JsonObject ?: return emptyList()

        outbound.mapValues { (_, directions) ->
            directions as? JsonObject
        }.map { (tag, d) ->
            val uplink = (d?.get("uplink") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val downlink = (d?.get("downlink") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            TagTraffic(
                tag = tag,
                uplinkBytes = uplink,
                downlinkBytes = downlink,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

/**
 * Fetches the payload from the loopback metrics listener.
 *
 * `HttpURLConnection` rather than a client library: this is one GET to
 * `127.0.0.1` and ARCHITECTURE.md §10.7 is explicit that a dependency added to
 * solve a small problem is attack surface in a VPN client.
 *
 * **This plain-HTTP GET needs `app/src/main/res/xml/network_security_config.xml`
 * to work at all below API 37.** Android's default cleartext policy for apps
 * targeting API 28+ (this app targets 36) refuses cleartext traffic outright,
 * and the platform's own implicit exception for loopback addresses only
 * begins at API 37 — see "Localhost configuration" at
 * https://developer.android.com/privacy-and-security/security-config, checked
 * 2026-09-22. Without that config file, every call on API 26 through 36 fails
 * with a platform-level rejection before it ever reaches xray's listener,
 * indistinguishable at this layer from "nothing is listening" (F1, review
 * 2026-09-22). If that file is ever removed, this function's failures go back
 * to being silent.
 *
 * @param warn how [noteFailure] reports a suspected structural failure. Real
 *   callers never pass this — the default is `Log.w`. The seam exists because
 *   this module's unit tests run as plain JVM tests with no Android framework
 *   mocked in, the same reason [space.getsub.service.log.LogcatReader] takes
 *   `spawn` as a parameter rather than calling a static factory directly.
 */
internal fun fetchMetricsPayload(
    port: Int,
    timeoutMillis: Int = 1_000,
    warn: (String) -> Unit = { msg -> Log.w(TAG, msg) },
): String? {
    val result =
        runCatching {
            val conn = URL("http://127.0.0.1:$port/debug/vars").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMillis
            conn.readTimeout = timeoutMillis
            try {
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }
    if (result.isSuccess) {
        failureStreaks.remove(port)
    } else {
        noteFailure(port, result.exceptionOrNull(), warn)
    }
    return result.getOrNull()
}

/**
 * How many *consecutive* failures on one port before [noteFailure] logs a
 * warning — roughly [STRUCTURAL_FAILURE_THRESHOLD] seconds at
 * `TrafficSamplerLoop.DEFAULT_INTERVAL_MILLIS`'s one-tick-per-second cadence.
 *
 * A fresh session allocates a new metrics port via `getFreePorts` (spec §2,
 * `TunnelService`), and xray takes a moment after that to actually start its
 * metrics listener — so the *first* poll or two failing is the ordinary
 * shape of session startup, not evidence of anything wrong. What distinguishes
 * "the feature is structurally broken" (F1's scenario: the platform refuses
 * every attempt, forever, e.g. because `network_security_config.xml` regressed
 * or a manifest merge dropped the attribute) from "xray hasn't opened the
 * socket yet" is persistence, not the exception's class — this project has no
 * device below API 37 to confirm what exception shape a cleartext-policy
 * rejection actually throws on every OS version this app ships to, so
 * pattern-matching a specific exception type here would be exactly the kind
 * of unsourced platform-behaviour guess CLAUDE.md and ARCHITECTURE.md §10.5
 * warn against. Ten seconds of continuous failure is not startup jitter on
 * any session that reaches [TrafficSamplerLoop] at all.
 */
private const val STRUCTURAL_FAILURE_THRESHOLD = 10

/**
 * Per-port because a new session gets a new port: a failure streak from a
 * session that has already ended cannot be mistaken for the new session's.
 * Entries are removed on success and on warning, so this cannot grow without
 * bound across the process's life the way a plain running counter would.
 *
 * Not `@Volatile` state on a data class — [ConcurrentHashMap.compute] does
 * the read-modify-write atomically per key, which is what matters here since
 * [fetchMetricsPayload] is called from `TrafficSamplerLoop`'s single ticking
 * coroutine and nothing else, but nothing about this file's contract forbids
 * a second caller.
 */
private val failureStreaks = ConcurrentHashMap<Int, Int>()

/**
 * §5.6: never logs config contents — only the failing exception's class name,
 * and only once per port, after [STRUCTURAL_FAILURE_THRESHOLD] *consecutive*
 * failures on it. Not logged per-tick: at one tick per second, that would be
 * exactly the noisy-per-second logging this fix was told to avoid, for a
 * feature that already runs continuously whenever the breakdown is enabled.
 */
private fun noteFailure(
    port: Int,
    cause: Throwable?,
    warn: (String) -> Unit,
) {
    val count = failureStreaks.compute(port) { _, prev -> (prev ?: 0) + 1 }
    if (count == STRUCTURAL_FAILURE_THRESHOLD) {
        warn(
            "metrics fetch has failed $STRUCTURAL_FAILURE_THRESHOLD consecutive times " +
                "(breakdown may be structurally broken, not just not-yet-listening): " +
                "${cause?.javaClass?.simpleName}",
        )
        failureStreaks.remove(port)
    }
}
