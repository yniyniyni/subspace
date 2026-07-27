// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

private const val XJ_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
private const val XJ_PBK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
private const val SECOND_UUID = "70cc48c5-b2f4-4a1e-9f3d-1123456789ab"
private const val THIRD_UUID = "70cc48c5-b2f4-4a1e-9f3d-2123456789ab"

class XrayJsonTest {
    @Test
    fun `extracts a reality vless outbound`() {
        val outcome = parseXrayJson(realityConfig)
        outcome.profiles.size shouldBe 1
        val out = outcome.profiles[0].outbound as VlessOutbound

        out.address shouldBe "host.example"
        out.port shouldBe 443
        out.uuid shouldBe XJ_UUID
        out.flow shouldBe "xtls-rprx-vision"
        val reality = out.stream.security as Security.Reality
        reality.serverName shouldBe "www.microsoft.com"
        reality.publicKey shouldBe XJ_PBK
        reality.shortId shouldBe "ab"
        reality.fingerprint shouldBe "chrome"
        reality.spiderX shouldBe "/"
    }

    @Test
    fun `ignores freedom blackhole and null protocol outbounds`() {
        val outcome =
            parseXrayJson(
                """
                {"outbounds":[
                  {"protocol":"freedom"},
                  {"protocol":"blackhole"},
                  {},
                  null,
                  ${validOutbound("first.example")}
                ]}
                """.trimIndent(),
            )

        outcome.profiles.map { it.name } shouldBe listOf("first.example")
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `malformed json is a typed failure not an exception`() {
        val outcome = parseXrayJson("{ not json")
        outcome.profiles shouldBe emptyList()
        outcome.failures.size shouldBe 1
        outcome.failures[0].index shouldBe 0
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `non-object json is a typed failure`() {
        val outcome = parseXrayJson("[]")
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `missing or non-array outbounds yields empty`() {
        parseXrayJson("{}").let { it shouldBe ParseOutcome.EMPTY }
        parseXrayJson("{\"outbounds\":null}").let { it shouldBe ParseOutcome.EMPTY }
        parseXrayJson("{\"outbounds\":true}").let { it shouldBe ParseOutcome.EMPTY }
    }

    @Test
    fun `accepts numeric and numeric-string ports`() {
        val outcome =
            parseXrayJson(
                """
                {"outbounds":[
                  ${validOutbound("number.example", port = "443")},
                  ${validOutbound("string.example", port = "\"8443\"")}
                ]}
                """.trimIndent(),
            )

        outcome.profiles.map { it.outbound.port } shouldBe listOf(443, 8443)
    }

    @Test
    fun `uses tcp none and safe tls defaults`() {
        val outcome =
            parseXrayJson(
                """
                {"outbounds":[
                  ${validOutbound("none.example", security = "none")},
                  ${validOutbound("tls.example", security = "tls")}
                ]}
                """.trimIndent(),
            )
        val none = (outcome.profiles[0].outbound as VlessOutbound).stream
        none.network shouldBe "tcp"
        none.security shouldBe Security.None
        val tls = (outcome.profiles[1].outbound as VlessOutbound).stream
        tls.network shouldBe "tcp"
        tls.security shouldBe Security.Tls("tls.example", "chrome", false)
    }

    @Test
    fun `unsupported or non-string security is a typed malformed failure`() {
        listOf("\"bogus\"", "true", "123", "{}").forEach { security ->
            val json =
                validOutbound("security.example", security = "none")
                    .replace("\"security\":\"none\"", "\"security\":$security")
            val failure = parseXrayJson("{\"outbounds\":[$json]}").failures.single()

            failure.index shouldBe 0
            failure.reason shouldBe ParseFailureReason.MalformedJson
            failure.detail shouldNotContain "bogus"
        }
    }

    @Test
    fun `parses every vnext destination and every user in order`() {
        val json =
            vlessOutboundJson(
                vnext(
                    "first.example",
                    user(XJ_UUID, "xtls-rprx-vision") + "," + user(SECOND_UUID),
                ) + "," + vnext("second.example", user(THIRD_UUID)),
            )
        val outcome = parseXrayJson("{\"outbounds\":[$json]}")

        outcome.profiles.map { (it.outbound as VlessOutbound).address } shouldBe
            listOf("first.example", "first.example", "second.example")
        outcome.profiles.map { (it.outbound as VlessOutbound).uuid } shouldBe
            listOf(XJ_UUID, SECOND_UUID, THIRD_UUID)
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `recovers after a bad vnext entry and reports its outbound index`() {
        val json =
            vlessOutboundJson(
                vnext("before.example", user(XJ_UUID)) +
                    ", {\"port\":443,\"users\":[${user(SECOND_UUID)}]}," +
                    vnext("after.example", user(THIRD_UUID)),
            )
        val outcome = parseXrayJson("{\"outbounds\":[$json]}")

        outcome.profiles.map { (it.outbound as VlessOutbound).address } shouldBe
            listOf("before.example", "after.example")
        outcome.failures.map { it.index } shouldBe listOf(0)
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `missing or empty vnext and users are typed failures`() {
        val missingVnext = "{\"protocol\":\"vless\",\"settings\":{}}"
        val emptyVnext = "{\"protocol\":\"vless\",\"settings\":{\"vnext\":[]}}"
        val missingUsers =
            vlessOutboundJson("{\"address\":\"missing-users.example\",\"port\":443}")
        val outcome = parseXrayJson("{\"outbounds\":[$missingVnext,$emptyVnext,$missingUsers]}")

        outcome.profiles shouldBe emptyList()
        outcome.failures.map { it.index } shouldBe listOf(0, 1, 2)
        outcome.failures.map { it.reason } shouldBe
            listOf(
                ParseFailureReason.MalformedJson,
                ParseFailureReason.MalformedJson,
                ParseFailureReason.MissingCredential,
            )
    }

    @Test
    fun `blank flow becomes null and tag falls back to address`() {
        val json = validOutbound("tag.example", tag = " ", flow = "\" \"")
        val outcome = parseXrayJson("{\"outbounds\":[$json]}")
        val profile = outcome.profiles.single()
        profile.name shouldBe "tag.example"
        (profile.outbound as VlessOutbound).flow shouldBe null
    }

    @Test
    fun `null and boolean required primitives fail without coercion`() {
        val addressNull = validOutbound("ignored").replace("\"address\":\"ignored\"", "\"address\":null")
        val uuidBoolean = validOutbound("ignored", uuid = "true")
        val portBoolean = validOutbound("ignored", port = "true")
        val outcome = parseXrayJson("{\"outbounds\":[$addressNull,$uuidBoolean,$portBoolean]}")

        outcome.profiles shouldBe emptyList()
        outcome.failures.map { it.index } shouldBe listOf(0, 1, 2)
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedJson
        outcome.failures[1].reason shouldBe ParseFailureReason.MissingCredential
        outcome.failures[2].reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `malformed optional fields use safe defaults`() {
        val json =
            validOutbound("optional.example", security = "tls").replace(
                "\"tlsSettings\":{}",
                "\"tlsSettings\":{\"serverName\":true,\"fingerprint\":null,\"allowInsecure\":\"true\"}",
            )
        val tls =
            (parseXrayJson("{\"outbounds\":[$json]}").profiles.single().outbound as VlessOutbound)
                .stream.security as Security.Tls

        tls.serverName shouldBe "optional.example"
        tls.fingerprint shouldBe "chrome"
        tls.allowInsecure shouldBe false
    }

    @Test
    fun `unsupported protocol is a typed redacted failure`() {
        val outcome = parseXrayJson("{\"outbounds\":[{\"protocol\":\"wireguard\"}]}")
        outcome.profiles shouldBe emptyList()
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.UnknownScheme
        outcome.failures[0].detail shouldNotContain "wireguard"
    }

    @Test
    fun `invalid reality key is a failure at outbound index`() {
        val json = realityConfig.replace(XJ_PBK, "AAEC").replace("{\n", "{\n")
        val outcome = parseXrayJson(json)
        outcome.profiles shouldBe emptyList()
        outcome.failures.single().index shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.InvalidRealityKey
    }

    @Test
    fun `bad outbounds do not discard good outbounds or reorder them`() {
        val json =
            """
            {"outbounds":[
              ${validOutbound("one.example")},
              {"protocol":"vless","settings":{}},
              ${validOutbound("two.example")},
              {"protocol":"wireguard"}
            ]}
            """.trimIndent()
        val outcome = parseXrayJson(json)

        outcome.profiles.map { it.name } shouldBe listOf("one.example", "two.example")
        outcome.failures.map { it.index } shouldBe listOf(1, 3)
    }

    @Test
    fun `malformed corpus never throws and never echoes input`() {
        val corpus =
            listOf(
                "",
                " ",
                "null",
                "true",
                "{",
                "[[]]",
                "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":null}]}",
                "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":true," +
                    "\"port\":443}]} }]}",
                "vless://$XJ_UUID@secret.example:443",
            )

        corpus.forEach { input ->
            val outcome = parseXrayJson(input)
            outcome.failures.forEach { failure ->
                if (input.isNotBlank()) failure.detail shouldNotContain input
            }
        }
    }

    private fun vlessOutboundJson(vnextEntries: String): String =
        """{"protocol":"vless","settings":{"vnext":[$vnextEntries]},"streamSettings":{"security":"none"}}"""

    private fun vnext(
        address: String,
        users: String,
    ): String = """{"address":"$address","port":443,"users":[$users]}"""

    private fun user(
        uuid: String,
        flow: String? = null,
    ): String {
        val flowField = flow?.let { ",\"flow\":\"$it\"" }.orEmpty()
        return """{"id":"$uuid"$flowField}"""
    }

    @Suppress("LongParameterList")
    private fun validOutbound(
        address: String,
        port: String = "443",
        uuid: String = "\"$XJ_UUID\"",
        tag: String? = null,
        flow: String = "\"xtls-rprx-vision\"",
        security: String = "reality",
    ): String {
        val tagField = tag?.let { "\"tag\":\"$it\"," } ?: ""
        val realitySettings =
            if (security == "reality") {
                "\"realitySettings\":{\"serverName\":\"www.microsoft.com\"," +
                    "\"publicKey\":\"$XJ_PBK\",\"shortId\":\"ab\",\"fingerprint\":\"chrome\",\"spiderX\":\"/\"}"
            } else {
                "\"tlsSettings\":{}"
            }
        return """{$tagField"protocol":"vless","settings":{"vnext":[{"address":"$address",
            "port":$port,"users":[{"id":$uuid,"flow":$flow}]}]},"streamSettings":{"security":"$security",
            $realitySettings}}""".replace("\n", "")
    }

    private val realityConfig =
        """
        {
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  { "address": "host.example", "port": 443,
                    "users": [ { "id": "$XJ_UUID", "flow": "xtls-rprx-vision", "encryption": "none" } ] }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": { "serverName": "www.microsoft.com", "publicKey": "$XJ_PBK",
                                     "shortId": "ab", "fingerprint": "chrome", "spiderX": "/" }
              }
            },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()
}
