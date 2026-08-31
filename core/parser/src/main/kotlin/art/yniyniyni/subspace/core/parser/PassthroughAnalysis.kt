// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Why a stored config cannot run as written.
 *
 * A closed vocabulary rather than a message: §5.6 forbids storing anything
 * derived from config contents, and §10.4 wants the reason to be actionable
 * rather than descriptive. The UI maps each member to a string resource.
 *
 * [CoreRejected] is the one member [analysePassthrough] never returns (design
 * §6.1): it is written by `:service` after `testXray`, not by structural
 * analysis, and lives in this same enum only so the UI renders one vocabulary
 * of reasons rather than two.
 */
public enum class PassthroughRejection {
    /** The stored text does not parse as a JSON object at all. */
    NotJson,

    /** No non-empty `outbounds` array — nothing to connect through. */
    NoOutbounds,

    /**
     * Several server outbounds and no balancer to select among them.
     *
     * Every profile parsed out of such an element carries the same bytes, so
     * running "this row as written" would run the same tunnel whichever row the
     * user tapped — trading a visible lie for a subtler one. A balancer is the
     * exception and is handled as [PassthroughAnalysis.isBalancer].
     */
    SeveralServers,

    /**
     * xray-core refused the composed config.
     *
     * Set by `:service`, not by [analysePassthrough] — the analyser is
     * structural and never calls the core. It lives in this enum because the UI
     * renders one vocabulary of reasons, not two.
     */
    CoreRejected,
}

/**
 * Why the app's routing and DNS cannot be applied *on top of* a config that is
 * otherwise runnable.
 *
 * Distinct from [PassthroughRejection]: these configs run perfectly on their
 * own. The target panel's own balancer entry is one of them (research §5b.1),
 * which is why this is not an eligibility failure.
 */
public enum class OverrideBlocker {
    /** No outbound is tagged exactly `proxy`, so our rules have nothing to name. */
    NoProxyTag,

    /** An outbound tag is blank or repeated, so a reference to it is ambiguous. */
    AmbiguousOutboundTags,
}

/**
 * A config is internally inconsistent in a way the user should hear about, but
 * which is theirs to make.
 */
public enum class PassthroughAdvisory {
    /** The config carries domain-matching rules that its own sniffing settings can never satisfy. */
    SniffingCannotServeOwnRules,

    /** `dns.servers` asks for FakeDNS but no inbound lists `fakedns` in `destOverride`. */
    FakeDnsWithoutSniffingOverride,

    /**
     * A `routing` rule or balancer names a tag that the config does not define.
     *
     * Traffic matching that rule is dropped when it fires — the core logs
     * `app/dispatcher: non existing outTag: <tag>` and the user sees a profile
     * that connects and carries nothing, with no message. Advisory rather than
     * a [PassthroughRejection] because the config still runs, and often runs
     * fine: the reference may name a path that never fires.
     *
     * Only this app can report it. xray-core **accepts** a dangling
     * `outboundTag` at config build and fails only when the rule fires —
     * established by instrumented run, see Question 3 of
     * `docs/agent/research/2026-08-25-m7-device-verification.md`. `testXray`
     * therefore never catches it.
     */
    DanglingRoutingReference,
}

/**
 * What a stored raw config is, structurally.
 *
 * §5.6: [outboundTags] are routing identifiers, not credentials, and are what
 * makes an override blocker explicable — but the generated `toString()` would
 * otherwise be the one place a config's shape reaches a log line, so it is
 * narrowed by hand.
 */
public data class PassthroughAnalysis(
    val rejection: PassthroughRejection?,
    val overrideBlocker: OverrideBlocker?,
    val advisories: List<PassthroughAdvisory>,
    val isBalancer: Boolean,
    val serverOutboundCount: Int,
    val outboundTags: List<String>,
) {
    override fun toString(): String =
        "PassthroughAnalysis(rejection=$rejection, overrideBlocker=$overrideBlocker, " +
            "advisories=$advisories, isBalancer=$isBalancer, servers=$serverOutboundCount, " +
            "tags=<redacted, ${outboundTags.size}>)"
}

/** Protocols that are infrastructure rather than a server the user chose. */
private val NON_SERVER_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

// ignoreUnknownKeys governs typed decodeFromString; the only use below is parseToJsonElement,
// which it never affects, so it is left out rather than left looking load-bearing.
private val LENIENT = Json { isLenient = true }

/**
 * Decides, from structure alone, whether a stored config can run as written.
 *
 * Never throws (§7): every malformed shape is a [PassthroughRejection]. Runs no
 * I/O and touches no Android API, which is why it lives here rather than in
 * `:core:xray` — `:core:data` and `:feature:profiles` both need it and neither
 * may depend on that module (§4).
 *
 * This decides *structure only*. Whether xray-core actually accepts the config
 * is a separate question answered by `testXray` at import; the two together are
 * the eligibility gate (design §6).
 */
public fun analysePassthrough(json: String): PassthroughAnalysis {
    val root = parseObject(json)
    val outbounds = root?.get("outbounds") as? JsonArray

    return when {
        root == null -> rejectedAs(PassthroughRejection.NotJson)
        outbounds.isNullOrEmpty() -> rejectedAs(PassthroughRejection.NoOutbounds)
        else -> analyseOutbounds(root, outbounds)
    }
}

/** Parses [json] as a JSON object, or null for anything that is not one — never throws. */
private fun parseObject(json: String): JsonObject? =
    try {
        LENIENT.parseToJsonElement(json) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

/** The eligibility decision once [root] is confirmed to carry a non-empty `outbounds` array. */
private fun analyseOutbounds(
    root: JsonObject,
    outbounds: JsonArray,
): PassthroughAnalysis {
    val objects = outbounds.filterIsInstance<JsonObject>()
    val tags = objects.map { it["tag"].stringOrNull().orEmpty() }
    val serverCount = objects.count { it["protocol"].stringOrNull() !in NON_SERVER_PROTOCOLS }

    val routing = root["routing"] as? JsonObject
    val isBalancer = routing.arrayOf("balancers").isNotEmpty()
    val rejection = if (serverCount > 1 && !isBalancer) PassthroughRejection.SeveralServers else null

    return PassthroughAnalysis(
        rejection = rejection,
        overrideBlocker = overrideBlockerFor(tags),
        advisories = advisoriesFor(root, routing, tags),
        isBalancer = isBalancer,
        serverOutboundCount = serverCount,
        outboundTags = tags,
    )
}

private fun rejectedAs(reason: PassthroughRejection): PassthroughAnalysis =
    PassthroughAnalysis(
        rejection = reason,
        overrideBlocker = null,
        advisories = emptyList(),
        isBalancer = false,
        serverOutboundCount = 0,
        outboundTags = emptyList(),
    )

private fun overrideBlockerFor(tags: List<String>): OverrideBlocker? =
    when {
        tags.any { it.isBlank() } || tags.size != tags.toSet().size -> OverrideBlocker.AmbiguousOutboundTags
        "proxy" !in tags -> OverrideBlocker.NoProxyTag
        else -> null
    }

/**
 * Research §5b.5: whether the config's own sniffing settings can serve its own
 * routing and DNS. Advisory, never a rejection — it is the config's own choice.
 */
private fun advisoriesFor(
    root: JsonObject,
    routing: JsonObject?,
    outboundTags: List<String>,
): List<PassthroughAdvisory> {
    val sniffedDestOverrides = sniffedDestOverrides(root)
    val hasDomainRules = routing.arrayOf("rules").filterIsInstance<JsonObject>().any { it["domain"] != null }
    val wantsFakeDns = (root["dns"] as? JsonObject).arrayOf("servers").any { it.stringOrNull() == "fakedns" }

    return buildList {
        if (hasDomainRules && sniffedDestOverrides.isEmpty()) {
            add(PassthroughAdvisory.SniffingCannotServeOwnRules)
        }
        if (wantsFakeDns && "fakedns" !in sniffedDestOverrides) {
            add(PassthroughAdvisory.FakeDnsWithoutSniffingOverride)
        }
        if (hasDanglingReference(root, routing, outboundTags)) {
            add(PassthroughAdvisory.DanglingRoutingReference)
        }
    }
}

/**
 * Whether any `routing` reference names something the config never defines.
 *
 * Three reference kinds, two namespaces:
 * - a rule's `outboundTag` names an **outbound** tag,
 * - a rule's `balancerTag` names a **balancer** tag,
 * - a balancer's `fallbackTag` names an **outbound** tag — the core's own
 *   refusal calls it `outTag` (device record F9, Pixel 8, 2026-08-31).
 *
 * `selector` is deliberately absent: it is a **prefix** match over outbound
 * tags, so `selector: ["proxy"]` legitimately selects `proxy-auto`,
 * `proxy-auto-2` and so on. Treating its entries as exact references would
 * report a defect that is not there — §10.4.
 *
 * Suppressed entirely when the config carries a `reverse` block: `ARCHITECTURE.md`
 * §"Passthrough execution" (line 331) lists `reverse` among the blocks
 * `RawConfigComposer` deliberately preserves as opaque, so such a config does
 * reach the core through passthrough. In xray's reverse-proxy shape, a rule's
 * `outboundTag` can legitimately name a `reverse` bridge/portal tag, which
 * lives outside `outbounds` — this analyser has no model of that namespace.
 * Widening the outbound-tag set to include reverse tags would need an
 * upstream citation this codebase does not have (§10.5); suppressing instead
 * makes no claim about how `reverse` resolves and fails toward silence
 * rather than toward a false accusation — the same §10.4 reasoning the
 * `selector` carve-out rests on.
 */
private fun hasDanglingReference(
    root: JsonObject,
    routing: JsonObject?,
    outboundTags: List<String>,
): Boolean {
    // `root["reverse"]` is a non-null JsonNull for an explicit `"reverse": null`, so this
    // must check for an actual object, not merely non-null, or that shape would disable the
    // whole dangling check for free.
    if ((root["reverse"] as? JsonObject) != null) return false

    val rules = routing.arrayOf("rules").filterIsInstance<JsonObject>()
    val balancers = routing.arrayOf("balancers").filterIsInstance<JsonObject>()
    val balancerTags = balancers.mapNotNull { it["tag"].stringOrNull() }.toSet()
    // Blank tags are filtered out: an outbound with no `tag` seeds outboundTags
    // with "" (analyseOutbounds' orEmpty()), and a rule carrying an equally
    // blank "outboundTag": "" must not resolve against that placeholder.
    val outbounds = outboundTags.filter { it.isNotBlank() }.toSet()

    val outboundRefs =
        rules.mapNotNull { it["outboundTag"].stringOrNull() } +
            balancers.mapNotNull { it["fallbackTag"].stringOrNull() }
    val balancerRefs = rules.mapNotNull { it["balancerTag"].stringOrNull() }

    return outboundRefs.any { it !in outbounds } || balancerRefs.any { it !in balancerTags }
}

/** `destOverride` values from every inbound whose `sniffing.enabled` is true. */
private fun sniffedDestOverrides(root: JsonObject): List<String> =
    root.arrayOf("inbounds").filterIsInstance<JsonObject>().flatMap { inbound ->
        val sniffing = inbound["sniffing"] as? JsonObject
        if (sniffing?.get("enabled").stringOrNull() != "true") {
            emptyList()
        } else {
            sniffing.arrayOf("destOverride").mapNotNull { it.stringOrNull() }
        }
    }

/** The array named [key] on this object, or empty if it is absent or a different shape. */
private fun JsonObject?.arrayOf(key: String): List<JsonElement> = (this?.get(key) as? JsonArray).orEmpty()

/** This element's string content, or null for JSON `null`, absence, or a non-string element. */
private fun JsonElement?.stringOrNull(): String? =
    when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> content
        else -> null
    }
