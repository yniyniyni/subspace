// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

/**
 * What this config uses to reach its server, decided from structure alone.
 *
 * Deliberately phrased in the config's own vocabulary rather than Xray's rule
 * keys: `:core:xray` decides that [ViaBalancer] means a `balancerTag` and
 * [ViaOutbound] means an `outboundTag`. That is the same split `RoutingRules.kt`
 * already makes when it keeps `RouteOutcome.outboundTag()` out of `:core:model`.
 *
 * [Resolved] exists so a caller cannot hand an unresolvable config to a rule
 * emitter: `XrayConfigGenerator.overrideBlocks` takes [Resolved], so the refusal
 * has to be dealt with before any rule can be written.
 */
public sealed interface OverrideTarget {
    /** A target that can actually be named in a rule. */
    public sealed interface Resolved : OverrideTarget {
        public val tag: String
    }

    /** Route through this balancer — the config selects among several servers. */
    public data class ViaBalancer(
        override val tag: String,
    ) : Resolved

    /** Route through this outbound — the config has one server. */
    public data class ViaOutbound(
        override val tag: String,
    ) : Resolved

    /**
     * Nothing can be named, so the app's routing and DNS cannot be applied.
     *
     * There is no fallback branch here on purpose. Every surveyed client has one
     * and none is safe in this setting: OneXray falls back to the first tagged
     * outbound without checking its protocol, which for the `dns-module`
     * catch-all would be a §5.2 leak, and v2rayNG falls back to a `proxy` tag
     * that is exactly what may not exist. See the research record's E4.
     */
    public data class Unresolvable(
        val reason: OverrideBlocker,
    ) : OverrideTarget
}

/**
 * Applies design §3.3's precedence rule. Pure, non-throwing, no I/O.
 *
 * **Resolve at connect, never store the answer.** M7's one Critical was a stored
 * verdict going stale across a subscription refresh; a stored target would go
 * stale identically the moment a refresh renames an outbound.
 *
 * @param reservedTags the outbound tags the override branch would append —
 *   `direct`/`block`, plus `dns-out` only when a DNS plan is present. A
 *   parameter rather than a constant because those are `:core:xray`'s wire
 *   vocabulary (§4), and because the correct answer genuinely depends on whether
 *   a DNS plan is in play.
 */
public fun resolveOverrideTarget(
    analysis: PassthroughAnalysis,
    reservedTags: Set<String>,
): OverrideTarget {
    // Gate 0. Only *non-blank* tags can collide: an untagged outbound was never a
    // candidate, and treating two of them as ambiguous would refuse the very
    // common `proxy` + untagged-freedom shape that runs today (design §3.3).
    val named = analysis.outboundTags.filter { it.isNotBlank() }
    if (named.size != named.toSet().size) {
        return OverrideTarget.Unresolvable(OverrideBlocker.AmbiguousOutboundTags)
    }
    return if (analysis.isBalancer) {
        resolveBalancer(analysis, reservedTags)
    } else {
        resolveSingleServer(analysis)
    }
}

/** Tags of outbounds that are a server the user chose, excluding untagged ones. */
private fun PassthroughAnalysis.serverOutboundTags(): List<String> =
    outboundProtocolsByTag
        .filterKeys { it.isNotBlank() }
        .filterValues { it !in NON_SERVER_PROTOCOLS }
        .keys
        .toList()

/**
 * Whether this balancer would take in any of [tags].
 *
 * `selector` is a **prefix** matcher over outbound tags, not an exact one —
 * `selector: ["proxy"]` legitimately selects `proxy-auto` and `proxy-auto-2`.
 * Sourced in `2026-08-25-remnawave-xray-json-and-balancers.md` §5b, relied on by
 * `hasDanglingReference`'s deliberate `selector` carve-out, and re-measured on a
 * Pixel 8 in `2026-09-01-balancer-tag-binding.md` (a `selector: ["member"]`
 * balancer carried traffic to outbound `member-1`).
 */
private fun BalancerSpec.selects(tags: Collection<String>): Boolean =
    selector.any { prefix ->
        tags.any { it.startsWith(prefix) }
    }

@Suppress("ReturnCount", "UnreachableCode") // K2 detekt misreads the deliberate typed early returns as unreachable.
private fun resolveBalancer(
    analysis: PassthroughAnalysis,
    reservedTags: Set<String>,
): OverrideTarget {
    val live = analysis.balancers.filter { it.selects(analysis.serverOutboundTags()) }
    // A6: the core accepts a balancer that selects nothing and then silently
    // drops every packet, so this is the one refusal we must make ourselves.
    if (live.isEmpty()) return OverrideTarget.Unresolvable(OverrideBlocker.BalancerSelectsNothing)
    // §3.4: a prefix that also captures an outbound we append would put our
    // `direct` (freedom) inside the user's balancer — proxied traffic leaving
    // unproxied, with no error and no log line.
    if (live.any { it.selects(reservedTags) }) {
        return OverrideTarget.Unresolvable(OverrideBlocker.TargetTagCollision)
    }
    if (live.size == 1) return OverrideTarget.ViaBalancer(live.single().tag)

    // Several live balancers: the config's own catch-all rules must name exactly
    // one of them. Not "the first" or "the last" — among rules that all match
    // everything, only the first fires, and which that is depends on evaluation
    // order this project has not measured. One distinct answer, or refuse.
    val liveTags = live.mapTo(mutableSetOf()) { it.tag }
    val named = analysis.catchAllBalancerRefs.filter { it in liveTags }.distinct()
    val only = named.singleOrNull() ?: return OverrideTarget.Unresolvable(OverrideBlocker.SeveralBalancers)
    return OverrideTarget.ViaBalancer(only)
}

private fun resolveSingleServer(analysis: PassthroughAnalysis): OverrideTarget {
    val only =
        analysis.serverOutboundTags().singleOrNull()
            ?: return OverrideTarget.Unresolvable(OverrideBlocker.NoResolvableTarget)
    return OverrideTarget.ViaOutbound(only)
}
