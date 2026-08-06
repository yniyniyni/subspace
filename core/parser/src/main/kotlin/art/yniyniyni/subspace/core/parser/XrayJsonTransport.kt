// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.TransportOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads a raw Xray outbound's `streamSettings` transport block into [TransportOptions] —
 * the raw-JSON counterpart of `VlessLink.kt`'s and `ClashTransport.kt`'s functions of the
 * same name.
 *
 * This did not exist before the xhttp fix, and its absence was the bug: `XrayJson.kt` read
 * `streamSettings.network` and built `StreamSettings` without a `transport`, so every
 * profile parsed out of a hand-written or panel-served config stored
 * [TransportOptions.None] whatever its transport actually was. That mattered more here
 * than on the share-link path, because a subscription serves whole configs — so a working
 * xhttp server lost its path and Host on import, then started presenting as "not supported
 * by this build yet" once `StoredProfile.connectable` began checking the network.
 *
 * §6 is why this is a projection rather than a full reading: the stored bytes are kept
 * verbatim for a `RAW_JSON` profile, and this typed view exists so
 * [art.yniyniyni.subspace.core.xray.XrayConfigGenerator] can emit the fields it knows.
 * Everything else in the block — padding placement, session-id tables, `tcpSettings`
 * header obfuscation — stays in the bytes and is deliberately not modelled (§10.5: a field
 * this parser invents is a field the generator would emit).
 *
 * Split into its own file rather than added to `XrayJson.kt` for the same reason
 * `ClashTransport.kt` is split out of `ClashYaml.kt`: that file is already at detekt's
 * function-count threshold.
 *
 * Key names verified against Xray-core v26.7.11 (`infra/conf/transport_method.go`):
 * `WebSocketConfig` (`path`, `host`, `headers`), `GRPCConfig` (`serviceName`),
 * `SplitHTTPConfig` (`path`, `host`, `mode`).
 */
internal fun transportOptions(
    streamSettings: JsonObject?,
    network: String,
): TransportOptions =
    when (network) {
        "ws", "websocket" -> webSocketOptions(streamSettings?.objectValue("wsSettings"))
        "grpc" -> {
            val serviceName = streamSettings?.objectValue("grpcSettings")?.nonBlankString("serviceName")
            if (serviceName == null) TransportOptions.None else TransportOptions.Grpc(serviceName)
        }

        // v26.7.11 aliases both names to one transport, and accepts the settings
        // object under either key (`XHTTPSettings` falls through to
        // `SplitHTTPSettings`), so a config using either spelling must be read.
        "xhttp", "splithttp" ->
            xhttpOptions(
                streamSettings?.objectValue("xhttpSettings")
                    ?: streamSettings?.objectValue("splithttpSettings"),
            )

        // tcp/raw included: `tcpSettings.header` models obfuscation this build does
        // not emit, and a half-reading would claim more than it delivers.
        else -> TransportOptions.None
    }

private fun webSocketOptions(ws: JsonObject?): TransportOptions.WebSocket {
    val headers =
        (ws?.objectValue("headers"))
            ?.entries
            ?.mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.stringContent()?.let { key to it }
            }?.toMap()
            .orEmpty()
    // `host` is shorthand for exactly this header, and an explicit `headers` entry
    // wins: it is the more specific statement of the two.
    val host = ws?.nonBlankString("host")
    return TransportOptions.WebSocket(
        path = ws?.nonBlankString("path") ?: "/",
        headers = if (host != null && !headers.containsKey("Host")) headers + ("Host" to host) else headers,
    )
}

/**
 * Note this returns options even when [xhttp] is null — unlike the ws and gRPC branches.
 *
 * A gRPC block with no `serviceName` genuinely carries nothing to preserve, so
 * [TransportOptions.None] is honest there. An xhttp transport always carries a path,
 * because Xray normalises an absent one to `/` (`GetNormalizedPath`), so `/` is what the
 * config *means* rather than a value invented for it.
 */
private fun xhttpOptions(xhttp: JsonObject?): TransportOptions.Xhttp =
    TransportOptions.Xhttp(
        path = xhttp?.nonBlankString("path") ?: "/",
        host = xhttp?.nonBlankString("host"),
        mode = xhttp?.nonBlankString("mode"),
    )
