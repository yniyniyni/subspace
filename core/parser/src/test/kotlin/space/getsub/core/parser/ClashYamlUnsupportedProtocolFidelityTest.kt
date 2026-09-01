// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.model.Security
import space.getsub.core.model.TransportOptions
import space.getsub.core.model.TrojanOutbound
import space.getsub.core.model.VmessOutbound

private const val FIDELITY_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

class ClashYamlUnsupportedProtocolFidelityTest {
    @Test
    fun `clash vmess tls accepts only the closed boolean vocabulary`() {
        val accepted =
            listOf(
                Triple("absent", null, Security.None),
                Triple("boolean true", "tls: true", Security.Tls("sni.example", "chrome", false)),
                Triple("boolean false", "tls: false", Security.None),
                Triple(
                    "quoted true normalized by yaml",
                    "tls: \"true\"",
                    Security.Tls("sni.example", "chrome", false),
                ),
                Triple("quoted false normalized by yaml", "tls: \"false\"", Security.None),
            )

        accepted.forEach { (name, tlsField, expected) ->
            withClue(name) {
                val outcome = parseClashYaml(vmessYaml(tlsField))
                val security = (outcome.profiles.single().outbound as VmessOutbound).stream.security

                outcome.failures shouldBe emptyList()
                security shouldBe expected
            }
        }

        val falseWithTransport = parseClashYaml(vmessYaml("tls: false", includeTransport = true))
        val outbound = falseWithTransport.profiles.single().outbound as VmessOutbound
        falseWithTransport.failures shouldBe emptyList()
        outbound.stream.security shouldBe Security.None
        outbound.stream.transport shouldBe
            TransportOptions.WebSocket("/rpc", mapOf("Host" to "cdn.example"))
    }

    @Test
    fun `clash vmess tls rejects every other present node shape`() {
        val rejected =
            listOf(
                "unknown string" to "tls: bogus",
                "empty string" to "tls: \"\"",
                "explicit null" to "tls: null",
                "number" to "tls: 1",
                "list" to "tls:\n      - true",
                "map" to "tls:\n      enabled: true",
            )

        rejected.forEach { (name, tlsField) ->
            withClue(name) {
                val outcome = parseClashYaml(vmessYaml(tlsField))

                outcome.profiles shouldBe emptyList()
                outcome.failures.single().reason shouldBe ParseFailureReason.MalformedYaml
                outcome.failures.single().detail shouldBe FailureDetail.Unsupported(DetailField.Security)
            }
        }
    }

    @Test
    fun `shadowsocks plugin fields are rejected instead of imported plain`() {
        val pluginDocuments =
            listOf(
                """
                proxies:
                  - name: Plugin
                    type: ss
                    server: c.example
                    port: 8388
                    cipher: aes-256-gcm
                    password: s3cret
                    plugin: v2ray-plugin
                    plugin-opts: tls;host=cdn.example
                """.trimIndent(),
                """
                proxies:
                  - name: Plugin
                    type: ss
                    server: c.example
                    port: 8388
                    cipher: aes-256-gcm
                    password: s3cret
                    plugin: null
                """.trimIndent(),
                """
                proxies:
                  - name: Plugin
                    type: ss
                    server: c.example
                    port: 8388
                    cipher: aes-256-gcm
                    password: s3cret
                    plugin-opts:
                      mode: websocket
                """.trimIndent(),
            )

        pluginDocuments.forEach { yaml ->
            val outcome = parseClashYaml(yaml)

            outcome.profiles shouldBe emptyList()
            outcome.failures.single().detail shouldBe FailureDetail.Unsupported(DetailField.Plugin)
        }
    }

    @Test
    fun `vmess and trojan retain clash websocket transport options`() {
        val yaml =
            """
            proxies:
              - name: VmessWs
                type: vmess
                server: a.example
                port: 443
                uuid: $FIDELITY_UUID
                network: ws
                ws-opts:
                  path: /vmess
                  headers:
                    Host: vmess-cdn.example
              - name: TrojanWs
                type: trojan
                server: b.example
                port: 443
                password: s3cret
                network: ws
                ws-opts:
                  path: /trojan
                  headers:
                    Host: trojan-cdn.example
            """.trimIndent()
        val outcome = parseClashYaml(yaml)

        (outcome.profiles[0].outbound as VmessOutbound).stream.transport shouldBe
            TransportOptions.WebSocket("/vmess", mapOf("Host" to "vmess-cdn.example"))
        (outcome.profiles[1].outbound as TrojanOutbound).stream.transport shouldBe
            TransportOptions.WebSocket("/trojan", mapOf("Host" to "trojan-cdn.example"))
    }

    @Test
    fun `clash transport options preserve explicit empty wire values`() {
        val yaml =
            """
            proxies:
              - name: VmessEmptyWs
                type: vmess
                server: a.example
                port: 443
                uuid: $FIDELITY_UUID
                network: ws
                ws-opts:
                  path: ""
                  headers:
                    Host: ""
              - name: TrojanEmptyGrpc
                type: trojan
                server: b.example
                port: 443
                password: s3cret
                network: grpc
                grpc-opts:
                  grpc-service-name: ""
            """.trimIndent()
        val outcome = parseClashYaml(yaml)

        (outcome.profiles[0].outbound as VmessOutbound).stream.transport shouldBe
            TransportOptions.WebSocket("", mapOf("Host" to ""))
        (outcome.profiles[1].outbound as TrojanOutbound).stream.transport shouldBe
            TransportOptions.Grpc("")
    }
}

private fun vmessYaml(
    tlsField: String?,
    includeTransport: Boolean = false,
): String {
    val base =
        """
        proxies:
          - name: VmessTls
            type: vmess
            server: a.example
            port: 443
            uuid: $FIDELITY_UUID
            sni: sni.example
        """.trimIndent()
    val tls = tlsField?.let { "\n    $it" }.orEmpty()
    val transport =
        if (includeTransport) {
            "\n    network: ws\n    ws-opts:\n      path: /rpc\n      headers:\n        Host: cdn.example"
        } else {
            ""
        }
    return base + tls + transport
}
