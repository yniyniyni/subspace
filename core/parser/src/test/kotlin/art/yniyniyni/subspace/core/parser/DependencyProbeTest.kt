// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
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
 *
 * A prior version of this file stopped there, and that was a real gap: every
 * test above calls plain runtime-library functions over generic element
 * trees (`parseToJsonElement`, `parseToYamlNode`, `jsonPrimitive`). None of
 * that requires the `org.jetbrains.kotlin.plugin.serialization` compiler
 * plugin — it only requires the two jars to resolve. Verified empirically:
 * with `alias(libs.plugins.kotlin.serialization)` removed from
 * `core/parser/build.gradle.kts` and nothing else changed, every test in
 * this file still passed. `Probe` below is the actual proof: without the
 * compiler plugin, a `@Serializable` class gets no generated `serializer()`
 * companion function, so `Probe.serializer()` is an unresolved reference and
 * `compileTestKotlin` fails outright — it cannot merely run and give a wrong
 * answer, the way the tests above could. Confirmed the reverse too: with the
 * plugin alias restored, the round-trip tests pass.
 */
class DependencyProbeTest {
    @Serializable
    private data class Probe(
        val ps: String,
    )

    @Test
    fun `json round-trips a Serializable class via the compiler-generated serializer`() {
        val encoded = Json.encodeToString(Probe.serializer(), Probe("name"))
        encoded shouldBe """{"ps":"name"}"""
        Json.decodeFromString(Probe.serializer(), encoded) shouldBe Probe("name")
    }

    @Test
    fun `yaml round-trips the same Serializable class via kaml`() {
        val encoded = Yaml.default.encodeToString(Probe.serializer(), Probe("name"))
        Yaml.default.decodeFromString(Probe.serializer(), encoded) shouldBe Probe("name")
    }

    @Test
    fun `json decodes over the raw element tree`() {
        val element = Json.parseToJsonElement("""{"ps":"name"}""")
        (element as JsonObject)["ps"]?.jsonPrimitive?.content shouldBe "name"
        element.toString() shouldBe """{"ps":"name"}"""
    }

    @Test
    fun `yaml decodes over the raw node tree`() {
        val node = Yaml.default.parseToYamlNode("proxies: []")
        node.shouldBeInstanceOf<YamlMap>()
    }
}
