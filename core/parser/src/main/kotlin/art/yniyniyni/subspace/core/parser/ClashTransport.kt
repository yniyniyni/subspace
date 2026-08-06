// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.TransportOptions
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar

/**
 * Reads `ws-opts` / `grpc-opts` off a Clash proxy entry into [TransportOptions].
 *
 * Split out of `ClashYaml.kt` rather than kept alongside `tls(common)`: it is
 * used by `vless` there, but living in its own file keeps that file under
 * detekt's TooManyFunctions threshold and leaves room for a future Clash
 * protocol to reuse it without growing that file further.
 */
internal fun transportOptions(
    proxy: YamlMap,
    network: String,
): TransportOptions =
    when (network) {
        "ws" -> {
            val opts = proxy.node("ws-opts") as? YamlMap
            val headers =
                (opts?.node("headers") as? YamlMap)
                    ?.entries
                    ?.mapNotNull { (key, value) ->
                        (value as? YamlScalar)?.content?.let { key.content to it }
                    }?.toMap()
                    .orEmpty()
            TransportOptions.WebSocket(path = opts?.text("path") ?: "/", headers = headers)
        }

        "grpc" -> {
            val name = (proxy.node("grpc-opts") as? YamlMap)?.text("grpc-service-name")
            if (name == null) TransportOptions.None else TransportOptions.Grpc(name)
        }

        else -> TransportOptions.None
    }
