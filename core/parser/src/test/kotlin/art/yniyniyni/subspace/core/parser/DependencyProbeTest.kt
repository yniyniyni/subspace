// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Throwaway probe. Deleted in Task 3.
 *
 * Exists because "the dependency is in the catalog" and "the compiler plugin
 * actually applied" are different claims, and AGP 9 has already made the
 * difference matter once in this project.
 *
 * Deviates from the task-2 brief's literal listing in two ways, both forced
 * by facts the brief could not have checked without a compiler in hand:
 *   - `(node != null) shouldBe true` doesn't compile: kaml 0.83.0's
 *     `parseToYamlNode` returns a non-nullable `YamlNode`, so the check is a
 *     compile-time-always-true condition, and `:core:parser` builds with
 *     `allWarningsAsErrors`. Replaced with an instance-of check that exercises
 *     the same "did it parse" question without asserting a tautology.
 *   - `element.jsonPrimitive` on a `{"ps":"name"}` object throws at runtime:
 *     `jsonPrimitive` casts to `JsonPrimitive` and a JSON object is not one.
 *     Rewritten to read the `"ps"` field itself, which is a primitive.
 */
class DependencyProbeTest {
    @Test
    fun `json decodes`() {
        val element = Json.parseToJsonElement("""{"ps":"name"}""")
        (element as JsonObject)["ps"]?.jsonPrimitive?.content shouldBe "name"
        element.toString() shouldBe """{"ps":"name"}"""
    }

    @Test
    fun `yaml decodes`() {
        val node = Yaml.default.parseToYamlNode("proxies: []")
        node.shouldBeInstanceOf<YamlMap>()
    }
}
