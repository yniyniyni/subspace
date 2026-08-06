// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.Profile
import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.SocksOutbound
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VlessOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound

/** Runtime settings that shape the config but do not belong to a stored profile. */
public data class TunnelSettings(
    /** Loopback SOCKS port, allocated at connect time. §10.6 forbids a literal. */
    val socksPort: Int,
    /** Advertised to both the `dns` block and `VpnService.Builder.addDnsServer` (§5.2). */
    val dnsServer: String,
    val enableSniffing: Boolean,
)

/**
 * The outcome of generating a config.
 *
 * A sealed result rather than a nullable String: "unsupported protocol" and
 * "generation failed" are different things to a user, and §10.4 says the
 * message is the only diagnostic they can hand back — §5.6 forbids logging
 * the config that would otherwise explain it.
 */
public sealed interface ConfigResult {
    public data class Ok(val json: String) : ConfigResult

    public data class Unsupported(val protocol: String) : ConfigResult
}

/**
 * Generates the Xray config JSON at connect time.
 *
 * ARCHITECTURE.md §6: the stored profile is not an Xray config. Generation is
 * deterministic — the same profile plus the same settings produces byte-identical
 * JSON — which is what makes golden-file tests and reviewable config diffs
 * possible.
 *
 * Emitted by hand rather than through a serialisation library. §10.7 says every
 * dependency in a VPN client is attack surface and to prefer stdlib, and writing
 * the string in order makes byte-identical output a property of this code rather
 * than of some library's map iteration order.
 *
 * Indentation is written literally rather than computed, so the source reads in
 * the shape of the JSON it emits. For a generator whose contract is byte-exact
 * output, that is worth more than avoiding the repetition.
 *
 * **Every key here was checked against the Xray-core schema**, and the whole
 * config is additionally run through libXray's own `testXray` in an instrumented
 * test. §10.5: agents confidently invent plausible Xray keys, and an unknown key
 * can be silently ignored or reject the entire config.
 */
public object XrayConfigGenerator {
    /**
     * Dispatches on the profile's protocol.
     *
     * `:core:xray` currently emits only VLESS. The `when` below is exhaustive
     * over the sealed [art.yniyniyni.subspace.core.model.Outbound] with no
     * `else` — adding a sixth protocol to the model is a compile error here
     * until this generator is taught to emit it, rather than a silent
     * fallthrough to a config that connects to nothing.
     *
     * Within VLESS it emits `tcp`, `ws`, `grpc` and `xhttp`. That set is mirrored by
     * `StoredProfile.connectable` in `:core:data`, which decides what the UI offers —
     * `:core:data` cannot depend on this module (§4), so the two are kept in step by
     * hand. Teaching this generator a new transport means widening that set too;
     * leaving it narrow is how a working xhttp server came to be labelled
     * "not supported by this build yet".
     */
    public fun generate(
        profile: Profile,
        settings: TunnelSettings,
    ): ConfigResult =
        when (val outbound = profile.outbound) {
            is VlessOutbound -> ConfigResult.Ok(generateVless(outbound, settings))
            is VmessOutbound -> ConfigResult.Unsupported("VMess")
            is TrojanOutbound -> ConfigResult.Unsupported("Trojan")
            is ShadowsocksOutbound -> ConfigResult.Unsupported("Shadowsocks")
            is SocksOutbound -> ConfigResult.Unsupported("SOCKS")
        }

    private fun generateVless(
        outbound: VlessOutbound,
        settings: TunnelSettings,
    ): String {
        val sb = StringBuilder()

        sb.appendLine("{")
        sb.appendLine("""  "log": {""")
        // §5.6, and this one was found on a device, not by reading: without
        // "access": "none", Xray writes a line to logcat for EVERY destination
        // the user reaches —
        //
        //   GoLog: from tcp:127.0.0.1:60680 accepted tcp:23.239.16.110:443 [socks-in >> proxy]
        //
        // gomobile pipes Go's stdout straight to logcat, so that is a live record
        // of the user's browsing readable by anything holding READ_LOGS and
        // captured by every bug report. loglevel alone does not suppress it —
        // the access log is a separate stream.
        sb.appendLine("""    "access": "none",""")
        // Do not raise this to "debug" for convenience: error-level output quotes
        // addresses too.
        sb.appendLine("""    "loglevel": "warning"""")
        sb.appendLine("""  },""")

        appendDns(sb, settings)
        appendInbounds(sb, settings)
        appendOutbounds(sb, outbound)

        sb.appendLine("""  "routing": {""")
        sb.appendLine("""    "domainStrategy": "IPIfNonMatch",""")
        // Empty in M1. Rule-based routing is M5.
        sb.appendLine("""    "rules": []""")
        sb.appendLine("""  }""")
        sb.append("}")

        return sb.toString()
    }

    /**
     * §5.2, half one. The other half is `VpnService.Builder.addDnsServer()`.
     *
     * Setting only one produces a partial leak that works on Wi-Fi and fails on
     * mobile, or the reverse. libXray v26.7.11 has no `setDNS`, so these two are
     * the only levers that exist.
     */
    private fun appendDns(
        sb: StringBuilder,
        settings: TunnelSettings,
    ) {
        sb.appendLine("""  "dns": {""")
        sb.appendLine("""    "servers": ["${settings.dnsServer}"]""")
        sb.appendLine("""  },""")
    }

    private fun appendInbounds(
        sb: StringBuilder,
        settings: TunnelSettings,
    ) {
        sb.appendLine("""  "inbounds": [""")
        sb.appendLine("""    {""")
        sb.appendLine("""      "tag": "socks-in",""")
        sb.appendLine("""      "protocol": "socks",""")
        // §6: loopback only. Never 0.0.0.0 — that turns the phone into an open
        // proxy for anyone on the same Wi-Fi.
        sb.appendLine("""      "listen": "127.0.0.1",""")
        sb.appendLine("""      "port": ${settings.socksPort},""")
        sb.appendLine("""      "settings": {""")
        sb.appendLine("""        "udp": true""")
        sb.appendLine("""      }${if (settings.enableSniffing) "," else ""}""")
        if (settings.enableSniffing) {
            sb.appendLine("""      "sniffing": {""")
            sb.appendLine("""        "enabled": true,""")
            sb.appendLine("""        "destOverride": ["http", "tls"]""")
            sb.appendLine("""      }""")
        }
        sb.appendLine("""    }""")
        sb.appendLine("""  ],""")
    }

    private fun appendOutbounds(
        sb: StringBuilder,
        out: VlessOutbound,
    ) {
        sb.appendLine("""  "outbounds": [""")
        sb.appendLine("""    {""")
        sb.appendLine("""      "tag": "proxy",""")
        sb.appendLine("""      "protocol": "vless",""")
        sb.appendLine("""      "settings": {""")
        sb.appendLine("""        "vnext": [""")
        sb.appendLine("""          {""")
        sb.appendLine("""            "address": "${out.address}",""")
        sb.appendLine("""            "port": ${out.port},""")
        sb.appendLine("""            "users": [""")
        sb.appendLine("""              {""")
        sb.appendLine("""                "id": "${out.uuid}",""")
        // VLESS has no transport encryption of its own; "none" is required, not
        // a weakening — TLS/REALITY in streamSettings is what secures it.
        sb.appendLine("""                "encryption": "none"${if (out.flow != null) "," else ""}""")
        if (out.flow != null) {
            sb.appendLine("""                "flow": "${out.flow}"""")
        }
        sb.appendLine("""              }""")
        sb.appendLine("""            ]""")
        sb.appendLine("""          }""")
        sb.appendLine("""        ]""")
        sb.appendLine("""      },""")
        appendStreamSettings(sb, out)
        sb.appendLine("""    },""")
        sb.appendLine("""    { "tag": "direct", "protocol": "freedom" },""")
        sb.appendLine("""    { "tag": "block", "protocol": "blackhole" }""")
        sb.appendLine("""  ],""")
    }

    private fun appendStreamSettings(
        sb: StringBuilder,
        out: VlessOutbound,
    ) {
        val stream = out.stream
        // The security block is last in the object unless a transport block
        // follows it, and JSON has no trailing commas — so whether it ends with
        // one is decided here rather than inside each security branch.
        val tail = if (stream.transport is TransportOptions.None) "" else ","
        sb.appendLine("""      "streamSettings": {""")
        sb.appendLine("""        "network": "${stream.network}",""")
        when (val security = stream.security) {
            is Security.Reality -> {
                sb.appendLine("""        "security": "reality",""")
                sb.appendLine("""        "realitySettings": {""")
                sb.appendLine("""          "serverName": "${security.serverName}",""")
                sb.appendLine("""          "publicKey": "${security.publicKey}",""")
                sb.appendLine("""          "shortId": "${security.shortId}",""")
                sb.appendLine("""          "fingerprint": "${security.fingerprint}",""")
                sb.appendLine("""          "spiderX": "${security.spiderX}"""")
                sb.appendLine("""        }$tail""")
            }

            is Security.Tls -> {
                sb.appendLine("""        "security": "tls",""")
                sb.appendLine("""        "tlsSettings": {""")
                sb.appendLine("""          "serverName": "${security.serverName}",""")
                sb.appendLine("""          "fingerprint": "${security.fingerprint}",""")
                sb.appendLine("""          "allowInsecure": ${security.allowInsecure}""")
                sb.appendLine("""        }$tail""")
            }

            Security.None -> {
                sb.appendLine("""        "security": "none"$tail""")
            }
        }
        appendTransportSettings(sb, stream.transport)
        sb.appendLine("""      }""")
    }

    /**
     * Emits the transport's own settings object, when the source specified one.
     *
     * Every key here is verified against Xray-core v26.7.11 —
     * `infra/conf/transport_method.go`, the version §14.3 pins: `WebSocketConfig`
     * (`path`, `host`, `headers`), `GRPCConfig` (`serviceName`), `SplitHTTPConfig`
     * (`path`, `host`, `mode`). §10.5 applies with full force in this function —
     * an invented key is either silently ignored, which presents as a tunnel that
     * connects and carries nothing, or rejects the entire config.
     *
     * [TransportOptions.None] emits **nothing**, including for a non-`tcp`
     * network. That is not a gap: `StreamConfig.Build` skips a transport whose
     * settings object is nil rather than erroring, and the dial then takes that
     * transport's registered defaults — which is what a link naming `type=ws`
     * and no path actually means. Writing `"path": ""` instead would assert an
     * empty path the source never claimed.
     *
     * Absent optional fields are likewise omitted rather than emitted empty.
     * `""` is a value, and for `host` in particular it is a different request on
     * the wire: omitted means Xray falls back to the dial address, where empty
     * would be a literal empty `Host` header.
     */
    private fun appendTransportSettings(
        sb: StringBuilder,
        transport: TransportOptions,
    ) {
        when (transport) {
            is TransportOptions.None -> Unit
            is TransportOptions.WebSocket -> appendWebSocketSettings(sb, transport)
            is TransportOptions.Grpc -> {
                sb.appendLine("""        "grpcSettings": {""")
                sb.appendLine("""          "serviceName": "${transport.serviceName}"""")
                sb.appendLine("""        }""")
            }

            is TransportOptions.Xhttp -> appendXhttpSettings(sb, transport)
        }
    }

    private fun appendWebSocketSettings(
        sb: StringBuilder,
        transport: TransportOptions.WebSocket,
    ) {
        sb.appendLine("""        "wsSettings": {""")
        sb.appendLine("""          "path": "${transport.path}"${if (transport.headers.isEmpty()) "" else ","}""")
        if (transport.headers.isNotEmpty()) {
            sb.appendLine("""          "headers": {""")
            // Sorted, like `OutboundMapper` sorts the same map before hashing it:
            // §6 requires byte-identical output for the same profile, and a Map's
            // iteration order is a property of its implementation, not its contents.
            val headers = transport.headers.toSortedMap()
            headers.entries.forEachIndexed { index, (name, value) ->
                val comma = if (index == headers.size - 1) "" else ","
                sb.appendLine("""            "$name": "$value"$comma""")
            }
            sb.appendLine("""          }""")
        }
        sb.appendLine("""        }""")
    }

    private fun appendXhttpSettings(
        sb: StringBuilder,
        transport: TransportOptions.Xhttp,
    ) {
        // Built as a list first: `host` and `mode` are independently optional, so
        // deciding each line's trailing comma in place would need to look ahead
        // past the other one.
        val fields = mutableListOf(""""path": "${transport.path}"""")
        transport.host?.let { fields += """"host": "$it"""" }
        transport.mode?.let { fields += """"mode": "$it"""" }

        sb.appendLine("""        "xhttpSettings": {""")
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ","
            sb.appendLine("""          $field$comma""")
        }
        sb.appendLine("""        }""")
    }
}
