// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.DnsValidation
import space.getsub.core.model.DomainStrategy
import space.getsub.core.model.Profile
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.model.Security
import space.getsub.core.model.ShadowsocksOutbound
import space.getsub.core.model.SocksOutbound
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VlessOutbound
import space.getsub.core.model.VmessOutbound
import space.getsub.core.parser.OverrideTarget

/** Runtime settings that shape the config but do not belong to a stored profile. */
public data class TunnelSettings(
    /** Loopback SOCKS port, allocated at connect time. §10.6 forbids a literal. */
    val socksPort: Int,
    /** Advertised to both the `dns` block and `VpnService.Builder.addDnsServer` (§5.2). */
    val dnsServer: String,
    val enableSniffing: Boolean,
    /**
     * The active routing rule set, or null when routing is off.
     *
     * Null is not a degraded mode: it produces the `"rules": []` block M1's
     * proven tunnel has always carried, byte for byte. Defaulted so that call
     * sites which do not route yet keep compiling.
     */
    val routing: RoutingRuleSet? = null,
    /**
     * Loopback HTTP proxy port, or null when app fetches are not proxied.
     *
     * §6's config shape has always read `socks [+ optional http]`; this is the
     * optional half. HTTP rather than a second SOCKS inbound because an HTTP
     * `CONNECT` carries the hostname to the proxy, so the *proxy* resolves it —
     * a Java SOCKS client may resolve locally first, which would leak the
     * subscription hostname while appearing to fetch through the tunnel (§5.2,
     * research §8).
     *
     * Allocated at connect time like [socksPort]; §10.6 forbids a literal.
     */
    val httpPort: Int? = null,
    /**
     * The resolved DNS plan, or null when nothing asks for DNS.
     *
     * Null is not a degraded mode: it produces the `"servers": ["1.1.1.1"]` block
     * M1's proven tunnel has always carried, byte for byte, with no `dns-out`
     * outbound and no prepended rules. Defaulted so call sites that do not resolve
     * DNS yet keep compiling.
     */
    val dns: DnsPlan? = null,
    /**
     * Loopback port for xray's metrics listener, or null when the per-tag
     * breakdown is off or no port could be allocated (M8.5 spec §2).
     *
     * Non-null is the only trigger for [metricsBlocks]: nothing here reads
     * `SettingsRepository.perTagBreakdown` directly, so a caller that never
     * allocates a port gets the exact M1 shape it always has, byte for byte.
     */
    val metricsPort: Int? = null,
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
/**
 * The three fixed outbound objects every generated config carries alongside
 * `proxy`, shared between [XrayConfigGenerator.appendOutbounds] (which always
 * emits `direct`/`block`, and `dns-out` when [TunnelSettings.dns] is set) and
 * [XrayConfigGenerator.overrideBlocks] (which hands the same three to the
 * passthrough path's override branch for outbounds the config lacks). One
 * definition rather than two hand-typed copies, so the pinned wire shape has
 * a single author (§10.5).
 */
private const val DIRECT_OUTBOUND_JSON = """{ "tag": "direct", "protocol": "freedom" }"""
private const val BLOCK_OUTBOUND_JSON = """{ "tag": "block", "protocol": "blackhole" }"""
private const val DNS_OUT_OUTBOUND_JSON = """{ "tag": "dns-out", "protocol": "dns" }"""

/**
 * The typed path's own server outbound tag.
 *
 * `appendOutbounds` emits `"tag": "proxy"` for every generated config, so the
 * typed path resolves to itself and its output is byte-identical to what this
 * generator produced before M7.5 — which the golden file test holds us to.
 */
private val TYPED_PATH_TARGET = OverrideTarget.ViaOutbound("proxy")

@Suppress("TooManyFunctions") // One object per wire shape (§6); splitting it would scatter the shape's single author.
public object XrayConfigGenerator {
    /**
     * Dispatches on the profile's protocol.
     *
     * `:core:xray` currently emits only VLESS. The `when` below is exhaustive
     * over the sealed [space.getsub.core.model.Outbound] with no
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
        appendOutbounds(sb, outbound, settings)

        appendRouting(sb, settings.routing, settings.dns, trailingComma = settings.metricsPort != null)
        appendMetrics(sb, settings.metricsPort)
        sb.append("}")

        return sb.toString()
    }

    /**
     * §5.2, half one. The other half is `VpnService.Builder.addDnsServer()`, and
     * M6.5 adds a third that matters more than either: the port-53 hijack in
     * [appendRouting], which is what makes any of this reach an app's resolver.
     *
     * libXray v26.7.11 has no `setDNS`, so the config is the only lever here.
     */
    private fun appendDns(
        sb: StringBuilder,
        settings: TunnelSettings,
    ) {
        sb.appendLine("""  "dns": ${dnsObject(settings)},""")
    }

    /**
     * §6's routing block. Empty in M1; M5 fills it from the active rule set.
     *
     * A null [routing] and a null [dns] reproduce the M1 block exactly —
     * `IPIfNonMatch` and an empty rule array — because the tunnel that block
     * belongs to is proven on hardware and this milestone does not get to drift
     * it. `XrayConfigGeneratorTest` pins that byte-for-byte.
     *
     * M6.5 prepends [dns]'s resolver and hijack rules ahead of the profile's own
     * — see [dnsRuleLines] for why the order matters.
     */
    private fun appendRouting(
        sb: StringBuilder,
        routing: RoutingRuleSet?,
        dns: DnsPlan?,
        trailingComma: Boolean,
    ) {
        // The typed path builds the `proxy` outbound itself (appendOutbounds), so
        // it is the one caller that knows its own target by construction. This is
        // now the single place the literal appears; before M7.5 it was written
        // out at three separate emitters.
        //
        // `namesFallthrough = false`: `appendOutbounds` writes `proxy` first, so
        // unmatched traffic already reaches it and a rule saying so would change
        // bytes the golden files pin. See `fallthroughRuleLines`.
        //
        // `trailingComma`: `routing` is the last field unless a metrics port
        // follows it (M8.5 spec §2) — see `appendMetrics`.
        sb.appendLine(
            """  "routing": ${routingObject(routing, dns, TYPED_PATH_TARGET, namesFallthrough = false)}""" +
                if (trailingComma) "," else "",
        )
    }

    /**
     * Appends `stats`/`policy`/`metrics`, only when [port] is non-null.
     *
     * A null port changes nothing: this is the one call site that can make the
     * typed path's output differ from the M1 shape the golden file pins, and
     * every other caller of [generate] gets that shape back byte-identical
     * (spec §2.4 makes the same promise on the passthrough side, in
     * `RawConfigComposer`).
     */
    private fun appendMetrics(
        sb: StringBuilder,
        port: Int?,
    ) {
        val p = port ?: return
        val entries = metricsBlocks(p).entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            val comma = if (index == entries.size - 1) "" else ","
            sb.appendLine("""  ${jsonString(key)}: $value$comma""")
        }
    }

    /**
     * The `routing` and `dns` objects the typed path writes, for the passthrough
     * path's override branch.
     *
     * Exposed rather than duplicated so a rule's shape has one author. The
     * passthrough path splices these in place of a config's own blocks; the two
     * paths therefore agree on what an app rule looks like by construction.
     *
     * [target] comes from the caller because only the caller knows whose config
     * this is: the rules that name the server have to name whatever *that*
     * document uses to reach it, and this generator never sees the document.
     *
     * `namesFallthrough = true`, and it is not optional: replacing a config's
     * `routing` deletes that config's own catch-all, and `RawConfigComposer`
     * leaves its `outbounds` in the document's order — so without a trailing
     * rule, unmatched traffic goes wherever that document happened to list
     * first. See `fallthroughRuleLines`.
     */
    public fun overrideBlocks(
        settings: TunnelSettings,
        target: OverrideTarget.Resolved,
    ): OverrideBlocks =
        OverrideBlocks(
            routingJson = routingObject(settings.routing, settings.dns, target, namesFallthrough = true),
            dnsJson = dnsObject(settings),
            extraOutboundsJson =
            buildList {
                add(DIRECT_OUTBOUND_JSON)
                add(BLOCK_OUTBOUND_JSON)
                if (settings.dns != null) add(DNS_OUT_OUTBOUND_JSON)
            },
        )

    private fun appendInbounds(
        sb: StringBuilder,
        settings: TunnelSettings,
    ) {
        val sniffing =
            if (!settings.enableSniffing) {
                null
            } else if (settings.dns?.fakeDns == true) {
                SniffingSettings(DEFAULT_SNIFFING.destOverride + "fakedns")
            } else {
                DEFAULT_SNIFFING
            }
        sb.appendLine("""  "inbounds": [""")
        sb.append(socksInboundJson(settings.socksPort, sniffing))
        sb.appendLine(if (settings.httpPort != null) "," else "")
        settings.httpPort?.let { port ->
            sb.appendLine(httpInboundJson(port))
        }
        sb.appendLine("""  ],""")
    }

    private fun appendOutbounds(
        sb: StringBuilder,
        out: VlessOutbound,
        settings: TunnelSettings,
    ) {
        sb.appendLine("""  "outbounds": [""")
        sb.appendLine("""    {""")
        sb.appendLine("""      "tag": "proxy",""")
        sb.appendLine("""      "protocol": "vless",""")
        sb.appendLine("""      "settings": {""")
        sb.appendLine("""        "vnext": [""")
        sb.appendLine("""          {""")
        sb.appendLine("""            "address": ${jsonString(out.address)},""")
        sb.appendLine("""            "port": ${out.port},""")
        sb.appendLine("""            "users": [""")
        sb.appendLine("""              {""")
        sb.appendLine("""                "id": ${jsonString(out.uuid)},""")
        // VLESS has no transport encryption of its own; "none" is required, not
        // a weakening — TLS/REALITY in streamSettings is what secures it.
        sb.appendLine("""                "encryption": "none"${if (out.flow != null) "," else ""}""")
        out.flow?.let { flow ->
            sb.appendLine("""                "flow": ${jsonString(flow)}""")
        }
        sb.appendLine("""              }""")
        sb.appendLine("""            ]""")
        sb.appendLine("""          }""")
        sb.appendLine("""        ]""")
        sb.appendLine("""      },""")
        appendStreamSettings(sb, out)
        sb.appendLine("""    },""")
        sb.appendLine("""    $DIRECT_OUTBOUND_JSON,""")
        val needsDnsOutbound = settings.dns != null
        sb.appendLine("""    $BLOCK_OUTBOUND_JSON${if (needsDnsOutbound) "," else ""}""")
        if (needsDnsOutbound) {
            // No settings object: the modern (rewriteNetwork/rewriteAddress/rules)
            // and legacy (network/address/nonIPQuery) field sets both exist at
            // v26.7.11, and emitting neither is stable across the deprecation.
            // Research §4: A/AAAA queries default to hijack into the built-in
            // resolver, which is exactly what this config wants.
            sb.appendLine("""    $DNS_OUT_OUTBOUND_JSON""")
        }
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
        sb.appendLine("""        "network": ${jsonString(stream.network)},""")
        when (val security = stream.security) {
            is Security.Reality -> {
                sb.appendLine("""        "security": "reality",""")
                sb.appendLine("""        "realitySettings": {""")
                sb.appendLine("""          "serverName": ${jsonString(security.serverName)},""")
                sb.appendLine("""          "publicKey": ${jsonString(security.publicKey)},""")
                sb.appendLine("""          "shortId": ${jsonString(security.shortId)},""")
                sb.appendLine("""          "fingerprint": ${jsonString(security.fingerprint)},""")
                sb.appendLine("""          "spiderX": ${jsonString(security.spiderX)}""")
                sb.appendLine("""        }$tail""")
            }

            is Security.Tls -> {
                sb.appendLine("""        "security": "tls",""")
                sb.appendLine("""        "tlsSettings": {""")
                sb.appendLine("""          "serverName": ${jsonString(security.serverName)},""")
                sb.appendLine("""          "fingerprint": ${jsonString(security.fingerprint)},""")
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
                sb.appendLine("""          "serviceName": ${jsonString(transport.serviceName)}""")
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
        sb.appendLine(
            """          "path": ${jsonString(transport.path)}${if (transport.headers.isEmpty()) "" else ","}""",
        )
        if (transport.headers.isNotEmpty()) {
            sb.appendLine("""          "headers": {""")
            // Sorted, like `OutboundMapper` sorts the same map before hashing it:
            // §6 requires byte-identical output for the same profile, and a Map's
            // iteration order is a property of its implementation, not its contents.
            val headers = transport.headers.toSortedMap()
            headers.entries.forEachIndexed { index, (name, value) ->
                val comma = if (index == headers.size - 1) "" else ","
                sb.appendLine("""            ${jsonString(name)}: ${jsonString(value)}$comma""")
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
        val fields = mutableListOf(""""path": ${jsonString(transport.path)}""")
        transport.host?.let { fields += """"host": ${jsonString(it)}""" }
        transport.mode?.let { fields += """"mode": ${jsonString(it)}""" }

        sb.appendLine("""        "xhttpSettings": {""")
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ","
            sb.appendLine("""          $field$comma""")
        }
        sb.appendLine("""        }""")
    }
}

/**
 * One `dns.servers` entry — a bare string when unscoped, an object when it carries
 * `domains`.
 *
 * `skipFallback` on the scoped domestic server is load-bearing: without it a
 * domestic miss falls through to the remote resolver, leaking in exactly the
 * direction the profile author tried to prevent (spec §7.2).
 */
private fun DnsServerSpec.render(): String {
    if (domains.isEmpty()) return jsonString(address)
    val domainList = domains.joinToString(", ", transform = ::jsonString)
    return """{ "address": ${jsonString(address)}, "domains": [$domainList], "skipFallback": $skipFallback }"""
}

/**
 * The rules that must precede the profile's own, in this order: the domestic
 * direct-match rule (if any), the remote proxy-match rule (if any), the
 * unconditional catch-all below, then the hijack rule.
 *
 * **The ordering is a loop hazard, not a preference, and claiming the traffic
 * is not optional.** The built-in resolver's own query to a configured server
 * is UDP to port 53. If nothing ahead of the hijack rule claimed it first, that
 * query would match the hijack and be fed straight back into the resolver that
 * issued it. [directMatch][DnsPlan.directMatch] and
 * [proxyMatch][DnsPlan.proxyMatch] are frequently both null — no profile
 * resolver, or a profile that names only hosts — so they cannot be what
 * guarantees this. The catch-all is: it is emitted unconditionally, on every
 * non-null plan, matching `inboundTag: ["dns-module"]` with no address filter,
 * so *all* of the resolver's own traffic is claimed before rule 3 can see it,
 * regardless of which matches happen to be set (spec §7.3).
 *
 * The catch-all's target is `proxy`, never `direct`: a resolver query sent to
 * `direct` leaves the tunnel, which is the exact §5.2 leak this plan exists to
 * prevent.
 */
private fun dnsRuleLines(
    dns: DnsPlan?,
    target: OverrideTarget.Resolved,
): List<String> {
    if (dns == null) return emptyList()
    val rules = mutableListOf<String>()
    dns.directMatch?.let { match -> rules += resolverRule(match, OverrideTarget.ViaOutbound("direct")) }
    dns.proxyMatch?.let { match -> rules += resolverRule(match, target) }
    rules += """{ "type": "field", "inboundTag": ["dns-module"], ${target.ruleTargetJson()} }"""
    rules += """{ "type": "field", "network": "tcp,udp", "port": 53, "outboundTag": "dns-out" }"""
    return rules
}

/** Matches one resolver's own traffic by address — an IP literal on `ip`, a hostname on `domain`. */
private fun resolverRule(
    match: String,
    target: OverrideTarget.Resolved,
): String {
    val field = if (DnsValidation.isAddressLiteral(match)) "ip" else "domain"
    return """{ "type": "field", "inboundTag": ["dns-module"], "$field": [${jsonString(match)}], """ +
        """${target.ruleTargetJson()} }"""
}

/**
 * The `dns` object's text, without the surrounding key or trailing comma —
 * [XrayConfigGenerator.appendDns] supplies both for the typed path;
 * [XrayConfigGenerator.overrideBlocks] hands the bare object to the
 * passthrough path's override branch. Top-level rather than a member so it
 * does not count against the object's function budget.
 */
private fun dnsObject(settings: TunnelSettings): String {
    val plan = settings.dns
    val sb = StringBuilder()
    sb.append("{\n")
    if (plan == null) {
        sb.appendLine("""    "servers": [${jsonString(settings.dnsServer)}]""")
        sb.append("""  }""")
        return sb.toString()
    }
    if (plan.hosts.isNotEmpty()) {
        sb.appendLine("""    "hosts": {""")
        // Sorted: a Map's iteration order is a property of its implementation
        // rather than of its contents, and §6 requires byte-determinism.
        val hosts = plan.hosts.toSortedMap().entries.toList()
        hosts.forEachIndexed { index, (host, address) ->
            val comma = if (index == hosts.size - 1) "" else ","
            sb.appendLine("""      ${jsonString(host)}: ${jsonString(address)}$comma""")
        }
        sb.appendLine("""    },""")
    }
    sb.appendLine("""    "servers": [""")
    val entries = buildList {
        if (plan.fakeDns) add(""""fakedns"""")
        plan.servers.forEach { add(it.render()) }
    }
    entries.forEachIndexed { index, entry ->
        val comma = if (index == entries.size - 1) "" else ","
        sb.appendLine("""      $entry$comma""")
    }
    sb.appendLine("""    ],""")
    sb.appendLine("""    "queryStrategy": "UseIP",""")
    sb.appendLine("""    "tag": "dns-module"""")
    sb.append("""  }""")
    return sb.toString()
}

/**
 * The `routing` object's text, without the surrounding key —
 * [XrayConfigGenerator.appendRouting] supplies it for the typed path;
 * [XrayConfigGenerator.overrideBlocks] hands the bare object to the
 * passthrough path's override branch. Top-level rather than a member so it
 * does not count against the object's function budget.
 */
private fun routingObject(
    routing: RoutingRuleSet?,
    dns: DnsPlan?,
    target: OverrideTarget.Resolved,
    namesFallthrough: Boolean,
): String {
    val strategy = routing?.domainStrategy ?: DomainStrategy.IP_IF_NON_MATCH
    // Last, and it has to be last: it matches everything, so anywhere earlier it
    // would shadow every rule behind it — including the port-53 hijack the DNS
    // plan depends on.
    val rules =
        dnsRuleLines(dns, target) +
            routing?.let { routingRuleLines(it, target) }.orEmpty() +
            fallthroughRuleLines(routing, target, namesFallthrough)

    val sb = StringBuilder()
    sb.append("{\n")
    sb.appendLine("""    "domainStrategy": ${jsonString(strategy.wireValue)},""")
    if (rules.isEmpty()) {
        sb.appendLine("""    "rules": []""")
    } else {
        sb.appendLine("""    "rules": [""")
        rules.forEachIndexed { index, rule ->
            val comma = if (index == rules.size - 1) "" else ","
            sb.appendLine("""      $rule$comma""")
        }
        sb.appendLine("""    ]""")
    }
    sb.append("""  }""")
    return sb.toString()
}
