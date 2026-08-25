// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private suspend fun validate(raw: String): Result<Unit> {
        val assetDir = File(context.filesDir, "geo").apply { mkdirs() }
        val controller = XrayController(geoAssetDir = assetDir)
        val result = RawConfigComposer.compose(raw, settings, assetDir.absolutePath, override = null)
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
     * accepts this at config build (`testXray` succeeds). A dangling
     * `fallbackTag` is therefore not caught by validation at all — it only
     * fails later, if and when the fallback actually fires. Recorded in
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
}
