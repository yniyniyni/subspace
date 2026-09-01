// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.feature.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val CODES_KEY = "codes"
private const val CODE_KEY = "code"
private const val RULE_COUNT_KEY = "ruleCount"

/**
 * One code `countGeoData` found in a geo database, as [GeoCategories] reads it.
 *
 * §5.6 is not in play here: a category code (`"cn"`, `"category-ads-all"`) is a
 * fixed, upstream-published vocabulary entry, not a site or address the user
 * visits — the same distinction [RuleSetRow.name] draws for a rule set's own
 * name.
 */
internal data class GeoCategory(val code: String, val ruleCount: Int)

/**
 * Reads the `<name>.json` sidecar `countGeoData` writes beside a validated
 * `.dat` — see `docs/agent/research/2026-08-11-geo-assets-and-xray-routing.md`
 * §5, which quotes `geo/count.go`'s shape:
 * `{"codes":[{"code":"cn","ruleCount":12345}],"categoryCount":N,"ruleCount":M}`.
 *
 * [RuleSetEditorViewModel] uses this to drive the rule set editor's
 * "browse categories" affordance — see [GeoCategory]'s own KDoc for why that is
 * not a §5.6 concern.
 *
 * Parses with `kotlinx-serialization-json`, not `org.json.JSONObject`. Fix
 * round 1 review: this file's first draft used `org.json` and added a
 * `testImplementation(libs.org.json)` purely to work around it being a stub on
 * the JVM unit-test classpath — a workaround `:core:xray`'s
 * `LibXrayInvokeTest` already considered and rejected by name for the
 * identical reason (see that file's own KDoc: *"a JSON dependency added
 * purely for tests... costs more than"* the alternative). `kotlinx-serialization-json`
 * needs no `kotlin.plugin.serialization` compiler plugin here — nothing below
 * is `@Serializable`, this only walks a [kotlinx.serialization.json.JsonElement]
 * tree — and it is already an `implementation` dependency of `:core:parser`,
 * `:core:data` and `:app`, so this module declaring it is not a new artifact
 * reaching the APK.
 */
internal object GeoCategories {
    /**
     * The codes in [file], or an empty list on any failure — a missing file (no
     * geo database installed yet), a corrupt one, or one that simply is not this
     * shape. A picker that degrades to free text is fine (brief step 3); an
     * editor that crashes reading a sidecar file is not.
     */
    @Suppress(
        "TooGenericExceptionCaught", // Any parse failure must degrade to an empty list, never propagate.
        "ReturnCount", // Three independent "give up, return empty" exits: no file, no codes array, unparseable.
    )
    fun read(file: File): List<GeoCategory> {
        if (!file.isFile) return emptyList()
        return try {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val codes = root[CODES_KEY]?.jsonArray ?: return emptyList()
            codes.map { element ->
                val entry = element.jsonObject
                GeoCategory(
                    code = entry.getValue(CODE_KEY).jsonPrimitive.content,
                    ruleCount = entry.getValue(RULE_COUNT_KEY).jsonPrimitive.int,
                )
            }
        } catch (_: Exception) {
            // kotlinx.serialization throws SerializationException for a malformed document, but
            // also IllegalArgumentException (a wrong-shaped element, e.g. "codes" not an array)
            // and NoSuchElementException (a missing "code"/"ruleCount" key) — a corrupt sidecar
            // file is exactly the case this function exists to survive, regardless of which one
            // it throws.
            emptyList()
        }
    }
}
