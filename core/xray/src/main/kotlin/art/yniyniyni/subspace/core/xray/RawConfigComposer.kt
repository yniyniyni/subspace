// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.xray

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Why a stored config could not be composed into something runnable. */
public enum class ComposeFailure {
    NotJson,
    NoOutbounds,

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
    private val LENIENT =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

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

        val sniffing =
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
            } else {
                sniffingOf(root)
            }
        val inbounds = inboundsJson(settings, sniffing)
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
     * The config's own sniffing block, or the app's default when it has none.
     *
     * Not our default unconditionally: the target panel sniffs `["http","tls"]`
     * with `domainStrategy: "AsIs"`, so adding `quic` would start matching its
     * RU domain rule against HTTP/3 traffic that is proxied today — a change to
     * the user's routing, made by us, with no error and no log line
     * (research §5b.5).
     */
    @Suppress("ReturnCount")
    private fun sniffingOf(root: JsonObject): SniffingSettings? {
        val inbound =
            (root["inbounds"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { (it["protocol"] as? JsonPrimitive)?.content == "socks" }
                ?: return DEFAULT_SNIFFING
        val sniffing = inbound["sniffing"] as? JsonObject ?: return DEFAULT_SNIFFING
        if ((sniffing["enabled"] as? JsonPrimitive)?.content != "true") return null
        // Final review I1: `.map { it.jsonPrimitive.content }` throws IllegalArgumentException on
        // any non-string element (an earlier review fixed this exact contract violation in the
        // override branch; this call site was missed). compose() must never throw on untrusted
        // config bytes — mapNotNull silently drops a malformed entry instead, same safety level
        // PassthroughAnalysis already applies to this same untrusted field via stringOrNull().
        val overrides =
            (sniffing["destOverride"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: return DEFAULT_SNIFFING
        return SniffingSettings(overrides)
    }

    private fun inboundsJson(
        settings: TunnelSettings,
        sniffing: SniffingSettings?,
    ): String {
        val socks = socksInboundJson(settings.socksPort, sniffing.takeIf { settings.enableSniffing })
        val http = settings.httpPort?.let(::httpInboundJson)
        return listOfNotNull(socks, http).joinToString(",\n")
    }

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
