// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed field accessors over `kotlinx.serialization`'s [JsonObject], shared by the raw
 * Xray JSON parser and its transport reader.
 *
 * Each returns null for a field that is absent *or* present with the wrong JSON type, which
 * is §7's never-throw rule expressed as a type: a config with `"port": {}` must produce a
 * parse failure for that one outbound, not an exception that loses the other 14 in the
 * document. Nothing here validates a value — [validatePort] and friends do that; these only
 * answer "is there a string/int/bool here".
 *
 * Extracted from `XrayJson.kt` when adding `xhttpSettings` reading pushed that file past
 * detekt's per-file function budget, which its own KDoc had already flagged as tight. They
 * were also about to be duplicated: `XrayJsonTransport.kt` needed the same string accessor
 * and had grown a private copy of it.
 *
 * The non-blank variant deliberately lives in `VmessLink.kt` rather than here, `internal`
 * for the same reason: it already existed there with identical semantics, and two functions
 * of the same name and receiver in one module is an overload ambiguity, not a convenience.
 */
internal fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.stringContent()

internal fun JsonPrimitive.stringContent(): String? = content.takeIf { isString }

internal fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.portValue(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * A JSON boolean, or null.
 *
 * Rejects a quoted `"true"`: Xray's own parser would not accept a string where a bool
 * belongs, so reading one here would make this parser more permissive than the core it
 * generates for — a profile that imports cleanly and then fails validation.
 */
internal fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
