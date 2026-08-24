// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

private const val FIDELITY_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

class ClashYamlUnsupportedProtocolFidelityTest {
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
