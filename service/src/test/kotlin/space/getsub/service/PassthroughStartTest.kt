// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test
import space.getsub.core.model.FailureReason
import space.getsub.core.parser.OverrideBlocker
import space.getsub.core.parser.OverrideTarget
import space.getsub.core.xray.ComposeFailure
import space.getsub.core.xray.ComposeResult
import space.getsub.core.xray.OverrideBlocks
import space.getsub.core.xray.RawConfigComposer
import space.getsub.core.xray.TunnelSettings

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

    // Spec §4.3, the precise silent bug the brief warns about: routing off
    // with a non-default DNS plan is an override (our routing/dns replace the
    // config's own) that must still resolve to the curated flat root, not the
    // rule set's own generation directory. A version of passthroughPlanFor
    // that (wrongly) keyed the asset dir off "does an override apply" instead
    // of off routingActive would return "/gen/7" here instead of "/geo", and
    // this is the only thing in the suite that would catch it.
    @Test
    fun `routing off with a dns plan overrides but still resolves the flat root`() {
        val plan =
            passthroughPlanFor(
                routingActive = false,
                dnsPlanPresent = true,
                activeAssetDir = "/gen/7",
                flatRoot = "/geo",
            )

        plan.assetDir shouldBe "/geo"
        plan.overrideApplies shouldBe true
    }

    @Test
    fun `active routing overrides and resolves its own asset dir`() {
        val plan =
            passthroughPlanFor(
                routingActive = true,
                dnsPlanPresent = false,
                activeAssetDir = "/gen/7",
                flatRoot = "/geo",
            )

        plan.assetDir shouldBe "/gen/7"
        plan.overrideApplies shouldBe true
    }

    @Test
    fun `no routing and no dns plan means no override and the flat root`() {
        val plan =
            passthroughPlanFor(
                routingActive = false,
                dnsPlanPresent = false,
                activeAssetDir = null,
                flatRoot = "/geo",
            )

        plan.assetDir shouldBe "/geo"
        plan.overrideApplies shouldBe false
    }

    private val balancerConfig =
        """
        {
          "routing": {
            "rules": [ { "type": "field", "network": "tcp,udp", "balancerTag": "Auto_Balancer" } ],
            "balancers": [ { "tag": "Auto_Balancer", "selector": ["proxy"] } ]
          },
          "outbounds": [
            { "tag": "proxy-auto", "protocol": "vless" },
            { "tag": "proxy-auto-2", "protocol": "vless" }
          ]
        }
        """.trimIndent()

    @Test
    fun `a balancer config resolves to its balancer instead of being refused`() {
        // Before M7.5 this returned MissingOverrideProxy: the document tags
        // nothing `proxy`, so the whole override was unavailable to it.
        overrideTargetFor(balancerConfig, dnsPlanPresent = true) shouldBe
            OverrideTarget.ViaBalancer("Auto_Balancer")
    }

    @Test
    fun `a lone server resolves to its own tag whatever it is called`() {
        val json = """{ "outbounds": [ { "tag": "proxy-auto", "protocol": "vless" } ] }"""

        overrideTargetFor(json, dnsPlanPresent = false) shouldBe OverrideTarget.ViaOutbound("proxy-auto")
    }

    @Test
    fun `dns-out is reserved only when a dns plan is present`() {
        reservedOverrideTags(dnsPlanPresent = true) shouldBe setOf("direct", "block", "dns-out")
        reservedOverrideTags(dnsPlanPresent = false) shouldBe setOf("direct", "block")
    }

    @Test
    fun `a config with no nameable target composes into a loud refusal`() {
        val json =
            """
            {
              "outbounds": [
                { "tag": "direct", "protocol": "freedom" },
                { "tag": "block", "protocol": "blackhole" }
              ]
            }
            """.trimIndent()

        overrideTargetFor(json, dnsPlanPresent = false) shouldBe
            OverrideTarget.Unresolvable(OverrideBlocker.NoResolvableTarget)
        compositionFailureReason(ComposeFailure.UnresolvableOverrideTarget) shouldBe
            FailureReason.PassthroughOverrideUnavailable
    }

    @Test
    fun `a reserved tag carrying the wrong protocol is still incompatible`() {
        // This half of the old check survives: the override appends `direct`,
        // `block` and `dns-out`, so a config defining one of those names with a
        // different protocol gives that tag different semantics.
        val json =
            """
            {
              "outbounds": [
                { "tag": "proxy-auto", "protocol": "vless" },
                { "tag": "direct", "protocol": "vless" }
              ]
            }
            """.trimIndent()

        passthroughOverrideFailure(json) shouldBe ComposeFailure.IncompatibleOverrideOutbound
    }

    @Test
    fun `an ordinary config passes the reserved-protocol check`() {
        passthroughOverrideFailure(config) shouldBe null
    }

    @Test
    fun `an override is refused when a reserved tag has incompatible semantics`() {
        val incompatible =
            listOf(
                """{ "tag": "proxy", "protocol": "vless" }, { "tag": "direct", "protocol": "blackhole" }""",
                """{ "tag": "proxy", "protocol": "vless" }, { "tag": "block", "protocol": "freedom" }""",
                """{ "tag": "proxy", "protocol": "vless" }, { "tag": "dns-out", "protocol": "freedom" }""",
            )

        incompatible.forEach { outbounds ->
            val failure = passthroughOverrideFailure("""{ "outbounds": [ $outbounds ] }""")
            failure shouldNotBe null
            compositionFailureReason(requireNotNull(failure)) shouldBe
                FailureReason.PassthroughOverrideUnavailable
        }
    }

    @Test
    fun `an outbound named proxy that is not a server is refused by resolution now`() {
        // Pre-M7.5 this was the guard's `proxyProtocol in INFRASTRUCTURE_PROTOCOLS`
        // arm. Resolution subsumes it: a freedom outbound is not a server the user
        // chose, so nothing is left to name.
        val json = """{ "outbounds": [ { "tag": "proxy", "protocol": "freedom" } ] }"""

        passthroughOverrideFailure(json) shouldBe null
        overrideTargetFor(json, dnsPlanPresent = false) shouldBe
            OverrideTarget.Unresolvable(OverrideBlocker.NoResolvableTarget)
    }

    @Test
    fun `a missing override target gets its own user-actionable failure reason`() {
        compositionFailureReason(ComposeFailure.UnresolvableOverrideTarget) shouldBe
            FailureReason.PassthroughOverrideUnavailable
        compositionFailureReason(ComposeFailure.NotJson) shouldBe FailureReason.ConfigGenerationFailed
    }

    // This mapping is the backstop two accepted M7 limitations rest on
    // (core validation skipped on the periodic subscription-refresh path, and
    // when geo assets are not yet installed) — both are acceptable only
    // because a core refusal names PassthroughRejectedAtConnect rather than
    // the generic ConfigRejected. Nothing else in the suite pins this.
    @Test
    fun `a core refusal is named for a passthrough row and generic otherwise`() {
        validationFailureReason(runsAsWritten = true) shouldBe FailureReason.PassthroughRejectedAtConnect
        validationFailureReason(runsAsWritten = false) shouldBe FailureReason.ConfigRejected
    }
}
