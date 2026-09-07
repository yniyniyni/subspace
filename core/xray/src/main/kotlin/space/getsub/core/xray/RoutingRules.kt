// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import space.getsub.core.model.RouteOutcome
import space.getsub.core.model.RoutingEntries
import space.getsub.core.model.RoutingRuleSet
import space.getsub.core.parser.OverrideTarget

/**
 * A trailing rule matching everything, so it decides where traffic no earlier
 * rule matched goes.
 *
 * `"network": "tcp,udp"` rather than an empty matcher: Xray rejects a rule with
 * no matching field at all, and tcp+udp is the complete set a TUN carries. A
 * `balancerTag` binds on exactly this shape — row `Q1a` of
 * `docs/agent/research/2026-09-01-balancer-tag-binding.md`, measured on a
 * Pixel 8 against its same-shape `outboundTag` control `C0`.
 */
private fun catchAllLine(target: OverrideTarget.Resolved): String =
    """{ "type": "field", "network": "tcp,udp", ${target.ruleTargetJson()} }"""

/**
 * The catch-all `GlobalProxy: "false"` needs, and why it is a rule rather than
 * an outbound reorder.
 *
 * `XrayConfigGenerator.appendOutbounds` emits `proxy` first, and Xray sends
 * traffic no rule matched to the first outbound — so the *typed* path has always
 * been implicitly `GlobalProxy: "true"`. Reordering `outbounds` to make `direct`
 * first would flip that default for every config, including the M1 shape proven
 * on hardware and every profile that does not set the field. A trailing rule
 * changes exactly the configs that ask for it and nothing else.
 *
 * The measurement behind "the first outbound": row `C1` of the balancer-tag
 * record, where a config carrying no rules at all sank into the `blackhole`
 * sitting first in its `outbounds` while its `freedom` member sat second.
 *
 * The override path cannot lean on that ordering — see [fallthroughRuleLines].
 */
private fun catchAllDirect(): String = catchAllLine(OverrideTarget.ViaOutbound("direct"))

/**
 * The `outboundTag`/`balancerTag` fragment a rule uses to name where traffic goes.
 *
 * One key, never both — they are mutually exclusive in Xray, and emitting text
 * rather than mutating a typed object makes that structural rather than
 * remembered. (v2rayNG has to null one field when it sets the other precisely
 * because it mutates beans; see the research record's E1.)
 */
internal fun OverrideTarget.Resolved.ruleTargetJson(): String =
    when (this) {
        is OverrideTarget.ViaBalancer -> """"balancerTag": ${jsonString(tag)}"""
        is OverrideTarget.ViaOutbound -> """"outboundTag": ${jsonString(tag)}"""
    }

/**
 * Where each outcome routes.
 *
 * `direct` and `block` are outbounds this app appends on every override, so they
 * are named literally. Only [RouteOutcome.PROXY] follows the config's own
 * vocabulary, because it is the only one that means "the server *this config*
 * reaches".
 *
 * Kept here rather than on [RouteOutcome] itself: `:core:model` has no business
 * knowing Xray's tag vocabulary.
 */
private fun RouteOutcome.targetFor(proxy: OverrideTarget.Resolved): OverrideTarget.Resolved =
    when (this) {
        RouteOutcome.BLOCK -> OverrideTarget.ViaOutbound("block")
        RouteOutcome.PROXY -> proxy
        RouteOutcome.DIRECT -> OverrideTarget.ViaOutbound("direct")
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
internal fun routingRuleLines(
    set: RoutingRuleSet,
    proxy: OverrideTarget.Resolved,
): List<String> =
    buildList {
        set.order.forEach { outcome ->
            val bucket = set.bucket(outcome)
            val target = outcome.targetFor(proxy)
            if (bucket.sites.isNotEmpty()) add(ruleLine("domain", bucket.sites, target))
            if (bucket.ips.isNotEmpty()) add(ruleLine("ip", bucket.ips, target))
        }
        // Xray takes the first match, so a catch-all anywhere earlier would
        // shadow every rule after it.
        if (set.globalProxy == false) add(catchAllDirect())
    }

/**
 * The one rule that says where traffic no other rule matched goes, for callers
 * that cannot rely on outbound order to say it for them.
 *
 * **The typed path passes `namesFallthrough = false` and must keep doing so.**
 * `appendOutbounds` writes `proxy` first, so its fallthrough is already the
 * proxy; emitting a rule saying the same thing would change bytes the golden
 * files pin for no behavioural gain.
 *
 * **The override path passes `true`, and needs to.** It replaces a stored
 * config's `routing` wholesale — which deletes that config's own catch-all —
 * while `RawConfigComposer` keeps the config's `outbounds` in the order the
 * document wrote them and only ever appends. Nothing then named the target for
 * unmatched traffic, so it went wherever the document happened to list first:
 * a `freedom` outbound in first position sent everything outside the tunnel
 * (§5.2), and a balancer config's unmatched traffic bypassed the balancer M7.5
 * exists to name. The app owns the rules on this branch by design (spec §4.2),
 * and owning them means saying what the default is rather than inheriting an
 * accident of the document's outbound order.
 *
 * `globalProxy == false` is the one case that needs nothing added:
 * [routingRuleLines] has already emitted its catch-all to `direct`, and a second
 * catch-all behind it would be dead — Xray takes the first match.
 *
 * A null [set] (routing off, DNS on) still gets the target: null is "the app has
 * no rule set", not "route nothing", and it is exactly what the typed path's
 * proxy-first outbound order means there too.
 */
internal fun fallthroughRuleLines(
    set: RoutingRuleSet?,
    proxy: OverrideTarget.Resolved,
    namesFallthrough: Boolean,
): List<String> =
    if (!namesFallthrough || set?.globalProxy == false) emptyList() else listOf(catchAllLine(proxy))

private fun ruleLine(
    field: String,
    values: List<String>,
    target: OverrideTarget.Resolved,
): String {
    val array = values.joinToString(", ", transform = ::jsonString)
    return """{ "type": "field", ${jsonString(field)}: [$array], ${target.ruleTargetJson()} }"""
}
