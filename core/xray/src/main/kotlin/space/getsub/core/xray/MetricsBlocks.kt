// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The three config blocks xray needs before it will count anything per tag.
 *
 * Emitted **only** when the user has turned the breakdown on
 * (`SettingsRepository.perTagBreakdown`, default off) — see that property for
 * why the default is what it is.
 *
 * All three are required. `metrics` alone yields an empty `stats` object,
 * because `getStatCounter` registers no counter unless
 * `policy.system.statsOutboundUplink` is set
 * (`app/proxyman/outbound/handler.go:34-45`, xray-core v26.7.11). Counters are
 * then named `outbound>>><tag>>>>traffic>>>uplink` and bucketed by outbound
 * tag, which since M7.5 means they follow the config's own vocabulary.
 *
 * @param port a loopback port from `getFreePorts` — ARCHITECTURE.md §10.6
 *   forbids a literal, and an ephemeral port makes the listener unguessable
 *   rather than safe.
 */
public fun metricsBlocks(port: Int): Map<String, JsonElement> =
    mapOf(
        "stats" to buildJsonObject { },
        "policy" to
            buildJsonObject {
                put(
                    "system",
                    buildJsonObject {
                        put("statsInboundUplink", JsonPrimitive(true))
                        put("statsInboundDownlink", JsonPrimitive(true))
                        put("statsOutboundUplink", JsonPrimitive(true))
                        put("statsOutboundDownlink", JsonPrimitive(true))
                    },
                )
            },
        "metrics" to
            buildJsonObject {
                put("listen", JsonPrimitive("127.0.0.1:$port"))
            },
    )

/**
 * The outbound tag a `metrics` block silently registers.
 *
 * `infra/conf/metrics.go:17-20`: when `tag` is empty but `listen` is set, the
 * tag defaults to `"Metrics"`, and `Start()` calls `ohm.AddHandler` for it
 * regardless. So this is a **fourth reserved outbound tag** beside
 * `direct`/`block`/`dns-out` — but only while the breakdown is on. A
 * passthrough config that defines an outbound of this name is not exotic, and
 * the failure without a check is a duplicate tag, which ARCHITECTURE.md §6
 * records the core as rejecting.
 */
public const val METRICS_RESERVED_TAG: String = "Metrics"
