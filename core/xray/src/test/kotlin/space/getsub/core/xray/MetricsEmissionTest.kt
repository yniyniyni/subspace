// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.core.model.Profile

/**
 * Task 14b: `metricsBlocks()` has no caller unless [XrayConfigGenerator] and
 * [RawConfigComposer] actually inject it. This pins the typed path's emission
 * decision — [RawConfigComposerMetricsTest] pins the passthrough path's.
 */
class MetricsEmissionTest {
    // Same fixture XrayConfigGeneratorTest already builds its settings from —
    // one shared VLESS profile, so this suite's golden text is not a second
    // hand-typed copy that can drift from the real one.
    private val profile = Profile(id = "id", name = "n", outbound = GOLDEN_OUTBOUND)

    private fun generateConfigForTest(metricsPort: Int?): String {
        val settings =
            TunnelSettings(
                socksPort = 10808,
                dnsServer = "1.1.1.1",
                enableSniffing = true,
                metricsPort = metricsPort,
            )
        val result = XrayConfigGenerator.generate(profile, settings)
        check(result is ConfigResult.Ok) { "expected ConfigResult.Ok, got $result" }
        return result.json
    }

    @Test
    fun `no metrics port means no stats blocks anywhere in the config`() {
        val json = generateConfigForTest(metricsPort = null)
        listOf("\"stats\"", "\"policy\"", "\"metrics\"").forEach { key ->
            assertFalse("$key must be absent when the breakdown is off", key in json)
        }
    }

    @Test
    fun `a metrics port emits all three blocks bound to loopback`() {
        val json = generateConfigForTest(metricsPort = 41234)
        assertTrue("\"stats\"" in json)
        assertTrue("\"policy\"" in json)
        assertTrue("127.0.0.1:41234" in json)
        // ARCHITECTURE.md §6: never all interfaces.
        assertFalse("0.0.0.0" in json)
    }

    @Test
    fun `the config with metrics blocks is well-formed JSON, not just substring matches`() {
        // The generator writes JSON by hand (§6 — no serialisation library for
        // the whole document), so a comma mistake at the routing/metrics splice
        // would not show up in the substring assertions above.
        val json = generateConfigForTest(metricsPort = 41234)
        Json.parseToJsonElement(json)
    }

    @Test
    fun `the emitted config is still deterministic`() {
        // ARCHITECTURE.md §6: same profile + same settings => byte-identical JSON.
        assertEquals(
            generateConfigForTest(metricsPort = 41234),
            generateConfigForTest(metricsPort = 41234),
        )
    }
}
