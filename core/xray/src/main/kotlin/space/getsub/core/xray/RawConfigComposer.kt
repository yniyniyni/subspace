// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a stored config could not be composed into something runnable. */
public enum class ComposeFailure {
    NotJson,
    NoOutbounds,

    /** The app override targets `proxy`, but the stored config defines no exact tag with that name. */
    MissingOverrideProxy,

    /** A reserved override tag exists, but its outbound protocol would give that tag different semantics. */
    IncompatibleOverrideOutbound,

    /**
     * [OverrideBlocks]'s `routingJson`/`dnsJson`/`extraOutboundsJson` did not
     * parse. Task 4's callers are expected to hand `compose` well-formed text
     * of its own making, but `compose` must not throw regardless of who built
     * [OverrideBlocks] — distinct from [NotJson] because that failure describes
     * the stored config itself, not the app's own override text.
     */
    InvalidOverride,
}

/** The outcome of composing a stored config. */
public sealed interface ComposeResult {
    public data class Ok(val json: String) : ComposeResult {
        // §5.6: this is the whole composed config — server UUID, REALITY
        // private key, the user's routing/dns text, all of it.
        override fun toString(): String = "Ok(<redacted, ${json.length} bytes>)"
    }

    public data class Failed(val reason: ComposeFailure) : ComposeResult
}

/**
 * The app's `routing` and `dns` blocks, when they replace the config's own.
 *
 * Carried as already-emitted JSON text rather than as a typed model, so
 * [XrayConfigGenerator] stays the single author of those bytes and the two
 * paths cannot drift in what a rule looks like.
 *
 * §5.6: routing entries are domains the user visits.
 */
public data class OverrideBlocks(
    val routingJson: String,
    val dnsJson: String,
    val extraOutboundsJson: List<String>,
) {
    override fun toString(): String = "OverrideBlocks(<redacted>)"
}

/**
 * Rewrites a stored raw Xray config into one this app can actually run.
 *
 * ARCHITECTURE.md §6's passthrough half. Unlike [XrayConfigGenerator], which
 * writes a config from a typed model, this **edits a parsed tree** — anything
 * the design does not name survives untouched, which is the entire point of
 * storing the bytes (§6: extraction is lossy and the loss is permanent).
 *
 * Four rewrites are unconditional, each forced by an existing invariant rather
 * than by preference:
 *
 * - `inbounds` — the config's listeners are on ports we did not allocate
 *   (§10.6; the target panel ships 10808/10809) and tun2socks dials ours.
 * - `env` — Go cannot see a Java `setenv`, so the asset directory travels in the
 *   invoke `env` object (§6).
 * - `log` — `access: "none"`, or gomobile pipes one line per destination the
 *   user reaches into logcat (§5.6, found on device during M1).
 * - `stats`/`policy`/`metrics` — a config's own metrics listener is an
 *   unaudited open port, and counters are M8's.
 *
 * Everything else depends on [override]: null runs the config's `routing` and
 * `dns` as written; non-null deletes both and substitutes the app's.
 */
public object RawConfigComposer {
    // ignoreUnknownKeys governs typed decodeFromString; every use below is parseToJsonElement,
    // which it never affects, so it is left out rather than left looking load-bearing.
    private val LENIENT = Json { isLenient = true }

    /**
     * @param assetDir what `xray.location.asset` must name — the active rule
     *   set's live generation when routing is on, the curated flat root
     *   otherwise. Resolved by the caller, which already computes it.
     */
    @Suppress("ReturnCount")
    public fun compose(
        rawJson: String,
        settings: TunnelSettings,
        assetDir: String,
        override: OverrideBlocks?,
    ): ComposeResult {
        val root =
            try {
                LENIENT.parseToJsonElement(rawJson) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return ComposeResult.Failed(ComposeFailure.NotJson)

        val outbounds = root["outbounds"] as? JsonArray
        if (outbounds.isNullOrEmpty()) return ComposeResult.Failed(ComposeFailure.NoOutbounds)

        val kept =
            root.filterKeys { it !in STRIPPED }
                .toMutableMap()

        kept["log"] = buildJsonObject {
            put("access", "none")
            put("loglevel", "warning")
        }
        kept["env"] = buildJsonObject { put("xray.location.asset", assetDir) }

        if (override != null) {
            val parsedOverride =
                try {
                    ParsedOverride(
                        routing = LENIENT.parseToJsonElement(override.routingJson),
                        dns = LENIENT.parseToJsonElement(override.dnsJson),
                        extraOutbounds = override.extraOutboundsJson.map(LENIENT::parseToJsonElement),
                    )
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return ComposeResult.Failed(ComposeFailure.InvalidOverride)

            kept["routing"] = parsedOverride.routing
            kept["dns"] = parsedOverride.dns
            kept["outbounds"] = JsonArray(outbounds + parsedOverride.extraOutbounds)
        }

        val preservedSniffing = if (override == null) sniffingOf(root) else null
        val generatedSniffing =
            if (override != null) {
                // The app already owns `dns` wholesale on this branch (just above), so
                // adding the one destOverride entry that block's fakeDns requires is
                // completing our own substitution, not second-guessing the config's
                // sniffing choice — unlike the pure-passthrough branch below, which
                // must never touch a config's own sniffing (spec §4.2, research §5b.5).
                if (settings.dns?.fakeDns == true) {
                    SniffingSettings(DEFAULT_SNIFFING.destOverride + "fakedns")
                } else {
                    DEFAULT_SNIFFING
                }
            } else if (preservedSniffing == null) {
                DEFAULT_SNIFFING
            } else {
                null
            }
        val inbounds = inboundsJson(settings, generatedSniffing, preservedSniffing, inboundTagsOf(root))
        return ComposeResult.Ok(render(kept, inbounds))
    }

    /** Keys removed outright before anything is added back. */
    private val STRIPPED = setOf("inbounds", "log", "env", "stats", "policy", "metrics")

    /** [OverrideBlocks], once its three JSON strings have parsed successfully. */
    private data class ParsedOverride(
        val routing: JsonElement,
        val dns: JsonElement,
        val extraOutbounds: List<JsonElement>,
    )

    /**
     * The config's own sniffing block, when its SOCKS inbound has one.
     *
     * Not our default unconditionally: the target panel sniffs `["http","tls"]`
     * with `domainStrategy: "AsIs"`, so adding `quic` would start matching its
     * RU domain rule against HTTP/3 traffic that is proxied today — a change to
     * the user's routing, made by us, with no error and no log line
     * (research §5b.5).
     */
    private fun sniffingOf(root: JsonObject): JsonObject? =
        (root["inbounds"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { (it["protocol"] as? JsonPrimitive)?.content == "socks" }
            ?.get("sniffing") as? JsonObject

    private fun inboundsJson(
        settings: TunnelSettings,
        generatedSniffing: SniffingSettings?,
        preservedSniffing: JsonObject?,
        tags: InboundTags,
    ): String {
        val socks =
            if (preservedSniffing != null) {
                socksInboundJsonPreservingSniffing(settings.socksPort, preservedSniffing, tags.socks)
            } else {
                socksInboundJson(
                    settings.socksPort,
                    generatedSniffing.takeIf { settings.enableSniffing },
                    tags.socks,
                )
            }
        val http = settings.httpPort?.let { httpInboundJson(it, tags.http) }
        return listOfNotNull(socks, http).joinToString(",\n")
    }

    /** The client-facing tags whose references survive in the config's own routing block. */
    private data class InboundTags(val socks: String, val http: String)

    /**
     * Preserves the original client inbound tags while replacing their listeners and ports.
     *
     * The pure branch keeps the config's routing tree untouched. If a rule names the original
     * SOCKS/HTTP tag through `inboundTag`, changing that tag on the injected inbound makes the rule
     * silently stop matching even though xray-core accepts the config. Defaults are used only when
     * the source has no usable tag for that protocol. The HTTP fallback is kept distinct from the
     * SOCKS tag so the injected pair can never create a duplicate-tag config by itself.
     */
    private fun inboundTagsOf(root: JsonObject): InboundTags {
        val socks = root.inboundTagFor("socks") ?: DEFAULT_SOCKS_INBOUND_TAG
        val requestedHttp = root.inboundTagFor("http") ?: DEFAULT_HTTP_INBOUND_TAG
        val http =
            when {
                requestedHttp != socks -> requestedHttp
                DEFAULT_HTTP_INBOUND_TAG != socks -> DEFAULT_HTTP_INBOUND_TAG
                else -> FALLBACK_HTTP_INBOUND_TAG
            }
        return InboundTags(socks = socks, http = http)
    }

    private fun JsonObject.inboundTagFor(protocol: String): String? =
        (this["inbounds"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { inbound -> inbound.stringValue("protocol") == protocol }
            ?.stringValue("tag")
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.stringValue(key: String): String? =
        when (val value = this[key]) {
            null, is JsonNull -> null
            is JsonPrimitive -> value.content
            else -> null
        }

    private const val DEFAULT_SOCKS_INBOUND_TAG = "socks-in"
    private const val DEFAULT_HTTP_INBOUND_TAG = "http-in"
    private const val FALLBACK_HTTP_INBOUND_TAG = "subspace-http-in"

    /**
     * Emits the tree with `inbounds` spliced in as text.
     *
     * Keys are written in sorted order so the same input always produces the
     * same bytes — the determinism contract §6 places on [XrayConfigGenerator],
     * met here by ordering rather than by hand-writing.
     */
    private fun render(
        kept: Map<String, JsonElement>,
        inbounds: String,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("""  "inbounds": [""")
        sb.appendLine(inbounds)
        sb.appendLine("""  ],""")
        val entries = kept.entries.sortedBy { it.key }
        entries.forEachIndexed { index, (key, value) ->
            val comma = if (index == entries.size - 1) "" else ","
            sb.appendLine("""  ${jsonString(key)}: $value$comma""")
        }
        sb.append("}")
        return sb.toString()
    }
}
