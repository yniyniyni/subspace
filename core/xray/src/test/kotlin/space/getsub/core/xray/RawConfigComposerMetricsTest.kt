// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.xray

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Task 14b, spec §2.4: the pure passthrough branch must never carry
 * `stats`/`policy`/`metrics` — it runs a config exactly as written, and
 * injecting these three would break that promise. Only the override branch,
 * which already replaces `routing`/`dns` wholesale, may carry them.
 */
class RawConfigComposerMetricsTest {
    private val config =
        """
        {
          "routing": { "rules": [] },
          "dns": { "servers": ["8.8.8.8"] },
          "outbounds": [ { "tag": "proxy", "protocol": "vless" } ]
        }
        """.trimIndent()

    private val override =
        OverrideBlocks(
            routingJson = """{ "domainStrategy": "IPIfNonMatch", "rules": [] }""",
            dnsJson = """{ "servers": ["1.1.1.1"] }""",
            extraOutboundsJson = emptyList(),
        )

    private fun settingsWith(metricsPort: Int?) =
        TunnelSettings(
            socksPort = 41080,
            dnsServer = "1.1.1.1",
            enableSniffing = true,
            metricsPort = metricsPort,
        )

    private fun compose(
        settings: TunnelSettings,
        override: OverrideBlocks?,
    ): JsonObject {
        val result = RawConfigComposer.compose(config, settings, assetDir = "/data/geo", override)
        check(result is ComposeResult.Ok) { "expected Ok, was $result" }
        return Json.parseToJsonElement(result.json) as JsonObject
    }

    @Test
    fun `the pure branch never carries metrics blocks, even with a port allocated`() {
        val out = compose(settingsWith(metricsPort = 41234), override = null)

        out["stats"] shouldBe null
        out["policy"] shouldBe null
        out["metrics"] shouldBe null
    }

    @Test
    fun `the override branch carries no metrics blocks when the breakdown is off`() {
        val out = compose(settingsWith(metricsPort = null), override)

        out["stats"] shouldBe null
        out["policy"] shouldBe null
        out["metrics"] shouldBe null
    }

    @Test
    fun `the override branch carries all three metrics blocks when a port is allocated`() {
        val out = compose(settingsWith(metricsPort = 41234), override)

        out["stats"].shouldNotBeNull()
        out["policy"].shouldNotBeNull()
        val metrics = out["metrics"] as JsonObject
        metrics.toString() shouldBe """{"listen":"127.0.0.1:41234"}"""
    }
}
