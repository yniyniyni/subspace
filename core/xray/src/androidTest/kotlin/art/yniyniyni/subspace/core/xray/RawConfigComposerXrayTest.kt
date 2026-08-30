// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * §10.1 in miniature: the unit tests prove the composer writes the JSON we
 * intended, and prove nothing about whether xray-core will run it.
 */
@RunWith(AndroidJUnit4::class)
class RawConfigComposerXrayTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val settings =
        TunnelSettings(socksPort = 41080, dnsServer = "1.1.1.1", enableSniffing = true, httpPort = 41081)

    // The target panel's balancer template, reduced to its structure. Addresses
    // and credentials are placeholders — §5.6, and the core does not dial during
    // validation, so they need only parse.
    private val balancerConfig =
        """
        {
          "burstObservatory": {
            "pingConfig": { "timeout": "3s", "interval": "2m", "sampling": 3,
                            "destination": "http://www.gstatic.com/generate_204", "connectivity": "" },
            "subjectSelector": ["proxy"]
          },
          "dns": { "servers": ["8.8.8.8"] },
          "log": { "loglevel": "info" },
          "routing": {
            "rules": [
              { "ip": ["8.8.8.8"], "port": "53", "type": "field", "balancerTag": "Auto_Balancer" },
              { "type": "field", "network": "tcp,udp", "balancerTag": "Auto_Balancer" }
            ],
            "balancers": [
              { "tag": "Auto_Balancer", "selector": ["proxy"],
                "strategy": { "type": "leastLoad",
                              "settings": { "maxRTT": "1500ms", "expected": 3,
                                            "baselines": ["200ms", "500ms"], "tolerance": 50 } },
                "fallbackTag": "FALLBACK_TAG" }
            ],
            "domainStrategy": "AsIs"
          },
          "inbounds": [
            { "tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks",
              "sniffing": { "enabled": true, "routeOnly": false, "destOverride": ["http", "tls"] } }
          ],
          "outbounds": [
            { "tag": "proxy-auto", "protocol": "vless",
              "settings": { "vnext": [ { "address": "192.0.2.1", "port": 443,
                "users": [ { "id": "00000000-0000-0000-0000-000000000001", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "none" } },
            { "tag": "proxy-auto-2", "protocol": "vless",
              "settings": { "vnext": [ { "address": "192.0.2.2", "port": 443,
                "users": [ { "id": "00000000-0000-0000-0000-000000000002", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "none" } },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    private suspend fun validate(raw: String, override: OverrideBlocks? = null): Result<Unit> {
        val assetDir = File(context.filesDir, "geo").apply { mkdirs() }
        val controller = XrayController(geoAssetDir = assetDir)
        val result = RawConfigComposer.compose(raw, settings, assetDir.absolutePath, override)
        check(result is ComposeResult.Ok) { "composer refused the fixture: $result" }
        val file = File(context.cacheDir, "passthrough-test.json").apply { writeText(result.json) }
        return runCatching { controller.validate(file) }
    }

    @Test
    fun theCoreAcceptsAComposedBalancerConfig() =
        runTest {
            val outcome = validate(balancerConfig.replace("FALLBACK_TAG", "proxy-auto"))

            // §5.6: XrayException's message can quote the config back
            // (LibXrayInvoke's own KDoc), so only the exception's class name is
            // safe to surface in a failure message — never `.exceptionOrNull()`.
            check(outcome.isSuccess) {
                "core rejected a well-formed balancer config: ${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }

    /**
     * Spec §6.2's one unresolved fact, and checklist row 9c.
     *
     * The target panel's template names `fallbackTag: "proxy"` while its
     * outbounds are `proxy-auto`, `proxy-auto-2`, … — `fallbackTag` is an exact
     * tag, not a prefix (research §3.2), so it points at nothing.
     *
     * **Observed 2026-08-25, Pixel 8, `connectedDebugAndroidTest`:** xray-core
     * accepts this at config build — `testXray` succeeds. `validate()` never
     * dials or runs traffic (see the fixture comment above and
     * `XrayController.validate`'s KDoc), so this test shows only that one bit:
     * accepted-at-build, not rejected-at-build. What happens if and when the
     * balancer's fallback actually fires was not exercised here and remains
     * open. Recorded in
     * `docs/agent/research/2026-08-25-remnawave-xray-json-and-balancers.md` §6.
     */
    @Test
    fun theCoreAcceptsAConfigWithADanglingFallbackTagAtBuild() =
        runTest {
            val outcome = validate(balancerConfig)

            check(outcome.isSuccess) {
                val exceptionName = outcome.exceptionOrNull()?.javaClass?.simpleName
                "expected the core to accept a dangling fallbackTag at config build " +
                    "(observed 2026-08-25 on Pixel 8) but it was rejected: $exceptionName"
            }
        }

    /**
     * Final review I9. Both existing cases above compose with `override = null` — the pure
     * passthrough branch. The override branch, which splices the app's own routing/dns/extra
     * outbounds into someone else's config, had never been handed to the real core.
     *
     * This also answers the open question ARCHITECTURE.md §6 and the §11 device checklist
     * recorded: does xray-core reject a `routing` rule whose `outboundTag` names an outbound the
     * config does not define, at config build — or accept it and simply never match?
     *
     * [balancerConfig]'s own outbounds are tagged `proxy-auto`/`proxy-auto-2` (plus `direct` and
     * `block`, which it also defines itself). `XrayConfigGenerator.overrideBlocks` never emits a
     * `proxy`-tagged outbound — that tag exists only in the app's own `XrayConfigGenerator.generate`
     * output, built from a *typed* profile's own outbound, which has nothing to do with a
     * passthrough config's outbounds. So a [RoutingRuleSet] with a PROXY bucket produces an
     * override routing rule naming `outboundTag: "proxy"`, and the composed config — the balancer
     * config's own outbounds plus the override's `direct`/`block` — never defines that tag.
     *
     * **Observed 2026-08-30, Pixel 8, `connectedDebugAndroidTest`:** xray-core REJECTS this at
     * config build — `testXray` throws `XrayException`. This is the opposite answer from
     * [theCoreAcceptsAConfigWithADanglingFallbackTagAtBuild]'s dangling `fallbackTag`: a balancer's
     * `fallbackTag` is validated lazily (only checked when the fallback fires), but a `routing`
     * rule's `outboundTag` is validated eagerly, at config build. Import-time `testXray`
     * (`PassthroughValidator`) therefore *does* catch this class of mistake before connect, unlike
     * the fallbackTag case.
     */
    @Test
    fun theCoreRejectsTheOverrideBranchWithADanglingProxyOutboundTag() =
        runTest {
            val routingSettings =
                settings.copy(
                    routing =
                    RoutingRuleSet(
                        name = "device-test",
                        buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("example.com"))),
                    ),
                )
            val override = XrayConfigGenerator.overrideBlocks(routingSettings)

            // Confirm the fixture actually poses the question before asking the core: the
            // composed config's outbounds must NOT include a "proxy" tag, while the override's
            // routing rule must reference exactly that tag.
            val composed = RawConfigComposer.compose(balancerConfig, routingSettings, "/data/geo", override)
            check(composed is ComposeResult.Ok) { "composer refused the fixture: $composed" }
            check("\"tag\": \"proxy\"" !in composed.json) {
                "fixture defines a \"proxy\" outbound after all — no longer exercises the dangling tag"
            }
            check("\"outboundTag\": \"proxy\"" in override.routingJson) {
                "override routing did not reference outboundTag \"proxy\" — fixture is not testing what it claims"
            }

            val outcome = validate(balancerConfig, override)

            check(outcome.isFailure) {
                "expected the core to reject a routing rule naming an undefined outboundTag " +
                    "(observed 2026-08-30 on Pixel 8) but it was accepted"
            }
            // §5.6: only the exception's class name is safe to surface (see the other tests'
            // comment above `theCoreAcceptsAComposedBalancerConfig`).
            check(outcome.exceptionOrNull() is XrayException) {
                "core rejected the config for an unexpected reason: " +
                    "${outcome.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }
}
