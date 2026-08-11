// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingEntries
import art.yniyniyni.subspace.core.model.RoutingRuleSet

private const val JSON_CONTROL_MAX = 0x1F
private const val JSON_ESCAPE_RADIX = 16
private const val JSON_ESCAPE_WIDTH = 4

/**
 * The Xray `outboundTag` each outcome routes to.
 *
 * All three outbounds have been emitted by `XrayConfigGenerator.appendOutbounds`
 * since M1 — `proxy` from the profile, `direct` (freedom) and `block`
 * (blackhole). This milestone is the first to reference them from a rule.
 *
 * Kept here rather than on [RouteOutcome] itself: `:core:model` has no business
 * knowing Xray's tag vocabulary.
 */
private fun RouteOutcome.outboundTag(): String =
    when (this) {
        RouteOutcome.BLOCK -> "block"
        RouteOutcome.PROXY -> "proxy"
        RouteOutcome.DIRECT -> "direct"
    }

/**
 * One JSON object per line, in the order they must appear in `routing.rules`.
 *
 * At most six: each outcome in [RoutingRuleSet.order] contributes a `domain`
 * rule when its bucket has sites and an `ip` rule when it has IPs. Empty buckets
 * contribute nothing — an empty `domain` array matches nothing and would be
 * noise in the config.
 *
 * **Entries keep their stored order.** The user's ordering carries meaning and
 * stored order is stable, so §6's byte-determinism holds without sorting them.
 * Contrast `appendWebSocketSettings`, which must sort because a `Map`'s
 * iteration order is a property of its implementation rather than of its
 * contents.
 *
 * `"type": "field"` is emitted although the pinned xray-core ignores it —
 * `infra/conf/router.go`'s `RouterRule` has no `Type` field and `parseRule`
 * performs no dispatch (research §6). It is the universally accepted form and
 * costs nothing; `XrayControllerTest` holds the claim that the core accepts what
 * this produces, not this comment.
 *
 * Entries are encoded as JSON strings here, even though [RoutingEntries]
 * rejects unsafe values before persistence. Callers can construct a
 * [RoutingRuleSet] directly, so escaping is a necessary second boundary:
 * malformed values remain data rather than closing a JSON string, injecting a
 * field, or invalidating the config. Values which need escaping are preserved;
 * valid stored entries retain their exact order and output.
 */
internal fun routingRuleLines(set: RoutingRuleSet): List<String> =
    set.order.flatMap { outcome ->
        val bucket = set.bucket(outcome)
        val tag = outcome.outboundTag()
        buildList {
            if (bucket.sites.isNotEmpty()) add(ruleLine("domain", bucket.sites, tag))
            if (bucket.ips.isNotEmpty()) add(ruleLine("ip", bucket.ips, tag))
        }
    }

private fun ruleLine(
    field: String,
    values: List<String>,
    tag: String,
): String {
    val array = values.joinToString(", ", transform = ::jsonString)
    return """{ "type": "field", "$field": [$array], "outboundTag": "$tag" }"""
}

private fun jsonString(value: String): String =
    buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code <= JSON_CONTROL_MAX) {
                        append("\\u")
                        append(char.code.toString(JSON_ESCAPE_RADIX).padStart(JSON_ESCAPE_WIDTH, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }
