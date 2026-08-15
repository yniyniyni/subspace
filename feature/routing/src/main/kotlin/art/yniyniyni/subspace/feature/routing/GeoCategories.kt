// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.routing

import org.json.JSONObject
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
            val codes = JSONObject(file.readText()).optJSONArray(CODES_KEY) ?: return emptyList()
            (0 until codes.length()).map { index ->
                val entry = codes.getJSONObject(index)
                GeoCategory(code = entry.getString(CODE_KEY), ruleCount = entry.getInt(RULE_COUNT_KEY))
            }
        } catch (_: Exception) {
            // org.json throws JSONException for a malformed document, but also plain
            // RuntimeException for some malformed shapes (e.g. a "codes" entry that is not
            // itself a JSON object) — a corrupt sidecar file is exactly the case this
            // function exists to survive, regardless of which one it throws.
            emptyList()
        }
    }
}
