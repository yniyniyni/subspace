// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import space.getsub.core.model.TagTraffic
import java.net.HttpURLConnection
import java.net.URL

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
 */
internal fun fetchMetricsPayload(
    port: Int,
    timeoutMillis: Int = 1_000,
): String? =
    runCatching {
        val conn = URL("http://127.0.0.1:$port/debug/vars").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMillis
        conn.readTimeout = timeoutMillis
        try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
