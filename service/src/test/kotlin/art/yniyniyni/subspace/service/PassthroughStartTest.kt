// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.xray.ComposeResult
import art.yniyniyni.subspace.core.xray.OverrideBlocks
import art.yniyniyni.subspace.core.xray.RawConfigComposer
import art.yniyniyni.subspace.core.xray.TunnelSettings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * The branch decision, isolated from the service so it can be tested on the JVM.
 * `TunnelService` itself needs a device; this pins the rule it applies.
 */
class PassthroughStartTest {
    private val settings =
        TunnelSettings(socksPort = 41080, dnsServer = "1.1.1.1", enableSniffing = true, httpPort = 41081)

    private val config =
        """
        {
          "routing": { "rules": [ { "domain": ["domain:ru"], "outboundTag": "direct" } ] },
          "dns": { "servers": ["8.8.8.8"] },
          "outbounds": [ { "tag": "proxy", "protocol": "vless" }, { "tag": "direct", "protocol": "freedom" } ]
        }
        """.trimIndent()

    @Test
    fun `with no rule set and no dns plan the config's own blocks survive`() {
        val json = (RawConfigComposer.compose(config, settings, "/geo", override = null) as ComposeResult.Ok).json

        json shouldContain "domain:ru"
        json shouldContain "8.8.8.8"
    }

    @Test
    fun `with an override the config's own blocks are gone`() {
        val override =
            OverrideBlocks(
                routingJson = """{ "domainStrategy": "IPIfNonMatch", "rules": [] }""",
                dnsJson = """{ "servers": ["1.1.1.1"] }""",
                extraOutboundsJson = listOf("""{ "tag": "block", "protocol": "blackhole" }"""),
            )

        val json = (RawConfigComposer.compose(config, settings, "/geo", override) as ComposeResult.Ok).json

        json shouldNotContain "domain:ru"
        json shouldNotContain "8.8.8.8"
        json shouldContain "1.1.1.1"
    }

    // Spec §4.3: the directory follows the ROUTING state, never the branch.
    // Routing off + a non-default DNS setting is an override that must still
    // resolve to the curated flat root.
    @Test
    fun `asset directory follows the routing state, not the override branch`() {
        assetDirFor(routingActive = false, assetDir = "/gen/7", flatRoot = "/geo") shouldBe "/geo"
        assetDirFor(routingActive = true, assetDir = "/gen/7", flatRoot = "/geo") shouldBe "/gen/7"
    }
}
