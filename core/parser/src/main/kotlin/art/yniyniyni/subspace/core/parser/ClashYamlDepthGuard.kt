// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.parser

// A real Clash file nests a handful of levels (map -> proxies list -> proxy
// map -> an opts sub-map); 64 is far above that and far below what overflows
// a stack. See yamlNestsTooDeep.
private const val YAML_MAX_NESTING_DEPTH = 64
private const val YAML_INDENT_WIDTH = 2

/**
 * The pre-kaml nesting guard for `ClashYaml.kt`.
 *
 * Split out rather than kept alongside `ClashYaml.kt`'s other helpers, for the
 * same reason `ClashYamlNode.kt` was: it is what keeps that file under
 * detekt's TooManyFunctions threshold, and this is generic depth measurement
 * rather than Clash-protocol logic.
 *
 * Whether [text] nests deeper, by indentation or by an inline dash chain,
 * than a real Clash file will ever need.
 *
 * `{`/`[` nesting in flow-style YAML (`{name: a, type: vless, ...}`) is
 * already bounded by `nestsTooDeep` in `SubscriptionParser.kt`, which runs on
 * every container shape before format detection. This guard exists for the
 * nesting flow-style brackets cannot see: Clash's block style nests through
 * indentation, and a compact block sequence such as `- - - - x` nests once
 * per dash with no bracket at all. Checked before the kaml call for the same
 * reason as that guard — `StackOverflowError` is an `Error`, not an
 * `Exception`, so `runCatching`/`try-catch` around the kaml call does not
 * catch it, and rejecting a document this deep can never be wrong: no real
 * Clash file needs anywhere close to it.
 *
 * Depth is measured per line rather than accumulated across lines — the
 * pathological cases are a single very deep line (many leading spaces, or a
 * long dash chain), and a line's own leading indent already reflects its true
 * nesting depth in a real, well-formed document.
 */
internal fun yamlNestsTooDeep(text: String): Boolean =
    text.lineSequence().any { line ->
        lineNestingDepth(line) > YAML_MAX_NESTING_DEPTH
    }

/**
 * The structural depth of one line: its leading indent, plus one more level
 * for every `- ` that immediately follows — a compact block sequence such as
 * `- - - x` nests once per dash with no further indentation to show it.
 */
private fun lineNestingDepth(line: String): Int {
    val indent = line.takeWhile { it == ' ' }.length / YAML_INDENT_WIDTH
    var depth = indent
    var index = indent * YAML_INDENT_WIDTH
    while (index < line.length && line.startsWith("- ", index)) {
        depth++
        index += 2
    }
    return depth
}
