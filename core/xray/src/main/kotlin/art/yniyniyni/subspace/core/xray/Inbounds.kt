// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
@file:Suppress("MatchingDeclarationName") // File groups the whole inbound pair, not just SniffingSettings.

package art.yniyniyni.subspace.core.xray

import kotlinx.serialization.json.JsonObject

/**
 * A SOCKS inbound's `sniffing` block.
 *
 * Traffic reaches the inbound from tun2socks addressed to an **IP**; without
 * sniffing no domain is ever recovered, and every `domain`/`geosite:` rule —
 * ours or a passthrough config's own — matches nothing. The config still runs
 * and the tunnel still carries traffic, which is why this is §10's category
 * rather than an obvious bug.
 */
internal data class SniffingSettings(val destOverride: List<String>)

/** What the typed path has always emitted. `fakedns` is appended when a DNS plan asks for it. */
internal val DEFAULT_SNIFFING = SniffingSettings(listOf("http", "tls", "quic"))

/**
 * The loopback SOCKS inbound, emitted identically by both config paths.
 *
 * §6: loopback only. Never `0.0.0.0` — that turns the phone into an open proxy
 * for anyone on the same Wi-Fi.
 *
 * [sniffing] is null when the caller wants no `sniffing` block at all. The
 * passthrough path passes the *config's own* block rather than
 * [DEFAULT_SNIFFING], because substituting ours changes which of the config's
 * rules match (spec §4.2).
 * [tag] defaults to the typed path's stable name; the passthrough composer
 * supplies the source config's original SOCKS tag so its untouched
 * `routing.rules[].inboundTag` references keep matching.
 *
 * Returns the inbound object with no trailing comma and no surrounding array;
 * the caller places it.
 */
internal fun socksInboundJson(
    port: Int,
    sniffing: SniffingSettings?,
    tag: String = "socks-in",
): String = socksInboundJson(port, sniffing, preservedSniffing = null, tag)

/** The same loopback inbound with a passthrough config's sniffing object kept unchanged. */
internal fun socksInboundJsonPreservingSniffing(
    port: Int,
    sniffing: JsonObject,
    tag: String,
): String = socksInboundJson(port, generatedSniffing = null, preservedSniffing = sniffing, tag)

private fun socksInboundJson(
    port: Int,
    generatedSniffing: SniffingSettings?,
    preservedSniffing: JsonObject?,
    tag: String,
): String {
    val hasSniffing = generatedSniffing != null || preservedSniffing != null
    val sb = StringBuilder()
    sb.appendLine("""    {""")
    sb.appendLine("""      "tag": ${jsonString(tag)},""")
    sb.appendLine("""      "protocol": "socks",""")
    sb.appendLine("""      "listen": "127.0.0.1",""")
    sb.appendLine("""      "port": $port,""")
    sb.appendLine("""      "settings": {""")
    sb.appendLine("""        "udp": true""")
    sb.appendLine("""      }${if (hasSniffing) "," else ""}""")
    when {
        preservedSniffing != null -> sb.appendLine("""      "sniffing": $preservedSniffing""")
        generatedSniffing != null -> {
            val overrides = generatedSniffing.destOverride.joinToString(", ") { jsonString(it) }
            sb.appendLine("""      "sniffing": {""")
            sb.appendLine("""        "enabled": true,""")
            sb.appendLine("""        "destOverride": [$overrides]""")
            sb.appendLine("""      }""")
        }
    }
    sb.append("""    }""")
    return sb.toString()
}

/**
 * The inbound `:core:network` dials so app fetches travel through the tunnel.
 *
 * Same loopback rule, and it matters more here: an HTTP proxy reachable from the
 * LAN is usable directly from any browser on the network. No `sniffing` block —
 * an HTTP `CONNECT` states its destination, so there is nothing to sniff.
 * [tag] follows the same preservation rule as [socksInboundJson].
 *
 * Returns the inbound object with no trailing comma and no surrounding array;
 * the caller places it.
 */
internal fun httpInboundJson(
    port: Int,
    tag: String = "http-in",
): String {
    val sb = StringBuilder()
    sb.appendLine("""    {""")
    sb.appendLine("""      "tag": ${jsonString(tag)},""")
    sb.appendLine("""      "protocol": "http",""")
    sb.appendLine("""      "listen": "127.0.0.1",""")
    sb.appendLine("""      "port": $port,""")
    sb.appendLine("""      "settings": {}""")
    sb.append("""    }""")
    return sb.toString()
}
