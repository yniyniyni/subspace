// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.ShadowsocksOutbound
import art.yniyniyni.subspace.core.model.TrojanOutbound
import art.yniyniyni.subspace.core.model.VmessOutbound
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

private const val CLASH_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

/** Base64 of the same Tokyo server as `config`'s first proxy, as a vmess link. */
private const val VMESS_BODY =
    "eyJ2IjoiMiIsInBzIjoiVG9reW8iLCJhZGQiOiJhLmV4YW1wbGUiLCJwb3J0IjoiNDQzIiwiaWQiOiI3MGNj" +
        "NDhjNS1iMmY0LTRhMWUtOWYzZC0wMTIzNDU2Nzg5YWIiLCJhaWQiOiIwIiwic2N5IjoiYXV0byIsIm5ldCI6InRjcCJ9"
private const val VMESS_LINK = "vmess://$VMESS_BODY"

class ClashYamlTest {
    private val config =
        """
        proxies:
          - name: "Tokyo"
            type: vmess
            server: a.example
            port: 443
            uuid: $CLASH_UUID
            alterId: 0
            cipher: auto
          - name: "Berlin"
            type: trojan
            server: b.example
            port: 443
            password: s3cret
          - name: "Oslo"
            type: ss
            server: c.example
            port: 8388
            cipher: aes-256-gcm
            password: s3cret
        """.trimIndent()

    @Test
    fun `parses all three proxy types`() {
        val outcome = parseClashYaml(config)
        outcome.profiles.size shouldBe 3
        outcome.failures.size shouldBe 0
    }

    @Test
    fun `maps vmess fields`() {
        val out = parseClashYaml(config).profiles[0].outbound as VmessOutbound
        out.address shouldBe "a.example"
        out.port shouldBe 443
        out.uuid shouldBe CLASH_UUID
        out.security shouldBe "auto"
    }

    @Test
    fun `maps trojan fields`() {
        val out = parseClashYaml(config).profiles[1].outbound as TrojanOutbound
        out.password shouldBe "s3cret"
    }

    @Test
    fun `maps shadowsocks cipher to method`() {
        val out = parseClashYaml(config).profiles[2].outbound as ShadowsocksOutbound
        out.method shouldBe "aes-256-gcm"
    }

    @Test
    fun `keeps the proxies in document order`() {
        val names = parseClashYaml(config).profiles.map { it.name }
        names shouldBe listOf("Tokyo", "Berlin", "Oslo")
    }

    @Test
    fun `falls back to the server as the name`() {
        val yaml =
            """
            proxies:
              - type: trojan
                server: b.example
                port: 443
                password: s3cret
            """.trimIndent()
        parseClashYaml(yaml).profiles.single().name shouldBe "b.example"
    }

    @Test
    fun `one bad proxy does not lose the others`() {
        // prependIndent, not a bare trimIndent: the appended entry has to stay
        // inside the `proxies:` sequence, or the document itself goes malformed
        // and the test proves nothing about per-entry recovery.
        val broken =
            """
            - name: "Broken"
              type: vmess
              server: d.example
              port: 443
            """.trimIndent().prependIndent("  ")
        val outcome = parseClashYaml(config + "\n" + broken)
        outcome.profiles.size shouldBe 3
        outcome.failures.size shouldBe 1
    }

    @Test
    fun `the failure index points at the offending proxy`() {
        val yaml =
            """
            proxies:
              - name: "Good"
                type: trojan
                server: a.example
                port: 443
                password: s3cret
              - name: "Broken"
                type: vmess
                server: b.example
                port: 443
            """.trimIndent()
        val failure = parseClashYaml(yaml).failures.single()
        failure.index shouldBe 1
        failure.reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `malformed yaml is a typed failure not an exception`() {
        val outcome = parseClashYaml("proxies:\n  - [unclosed")
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedYaml
    }

    @Test
    fun `a config with no proxies key yields an empty outcome`() {
        parseClashYaml("rules: []").profiles.size shouldBe 0
    }

    @Test
    fun `a document that is not a mapping is a typed failure`() {
        val outcome = parseClashYaml("- just\n- a\n- list")
        outcome.profiles.size shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedYaml
    }

    /**
     * kaml's `YamlMap.get<T>` throws `IncorrectTypeException` when the key is
     * present with a different node type — verified against 0.83.0 with javap.
     * §7 forbids throwing, so every node read here goes through a safe cast.
     */
    @Test
    fun `a proxies key that is not a list is a typed failure not an exception`() {
        val outcome = parseClashYaml("proxies: notalist")
        outcome.profiles.size shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedYaml
    }

    @Test
    fun `a proxy entry that is not a mapping is a failure not a crash`() {
        val yaml = "proxies:\n  - notamapping"
        val outcome = parseClashYaml(yaml)
        outcome.profiles.size shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedYaml
    }

    /**
     * Same `IncorrectTypeException` hazard, one level down. A key holding a
     * list reads as absent, so the field's own diagnostic is what surfaces.
     */
    @Test
    fun `a proxy field of the wrong shape is a failure not a crash`() {
        val yaml =
            """
            proxies:
              - name: "Nested"
                type: trojan
                server: a.example
                port:
                  - 443
                password: s3cret
            """.trimIndent()
        val outcome = parseClashYaml(yaml)
        outcome.profiles.size shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `an unsupported proxy type is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Wire"
                type: wireguard
                server: a.example
                port: 443
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.UnknownScheme
    }

    @Test
    fun `a proxy with no type is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Typeless"
                server: a.example
                port: 443
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.UnknownScheme
    }

    @Test
    fun `an out-of-range port is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Big"
                type: trojan
                server: a.example
                port: 70000
                password: s3cret
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `a non-numeric port is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Words"
                type: trojan
                server: a.example
                port: notaport
                password: s3cret
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `tls true becomes a TLS security with the sni`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: $CLASH_UUID
                tls: true
                sni: sni.example
            """.trimIndent()
        val out = parseClashYaml(yaml).profiles.single().outbound as VmessOutbound
        val tls = out.stream.security as Security.Tls
        tls.serverName shouldBe "sni.example"
        tls.allowInsecure shouldBe false
    }

    @Test
    fun `servername is accepted as the sni alias`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: $CLASH_UUID
                tls: true
                servername: alias.example
            """.trimIndent()
        val out = parseClashYaml(yaml).profiles.single().outbound as VmessOutbound
        (out.stream.security as Security.Tls).serverName shouldBe "alias.example"
    }

    @Test
    fun `skip-cert-verify carries into allowInsecure`() {
        val yaml =
            """
            proxies:
              - name: "Berlin"
                type: trojan
                server: b.example
                port: 443
                password: s3cret
                skip-cert-verify: true
            """.trimIndent()
        val out = parseClashYaml(yaml).profiles.single().outbound as TrojanOutbound
        (out.stream.security as Security.Tls).allowInsecure shouldBe true
    }

    @Test
    fun `vmess without tls has no security`() {
        val out = parseClashYaml(config).profiles[0].outbound as VmessOutbound
        out.stream.security shouldBe Security.None
    }

    @Test
    fun `the network defaults to tcp and is carried when present`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: $CLASH_UUID
                network: ws
            """.trimIndent()
        val out = parseClashYaml(yaml).profiles.single().outbound as VmessOutbound
        out.stream.network shouldBe "ws"
        (parseClashYaml(config).profiles[0].outbound as VmessOutbound).stream.network shouldBe "tcp"
    }

    @Test
    fun `an absent alterId defaults to zero`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: $CLASH_UUID
            """.trimIndent()
        (parseClashYaml(yaml).profiles.single().outbound as VmessOutbound).alterId shouldBe 0
    }

    /** Task 8's ruling, applied here: a present but unreadable alterId is data loss, not a default. */
    @Test
    fun `a malformed alterId is a typed failure rather than a silent zero`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: $CLASH_UUID
                alterId: lots
            """.trimIndent()
        val outcome = parseClashYaml(yaml)
        outcome.profiles.size shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedYaml
    }

    @Test
    fun `a non-zero alterId is preserved`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: $CLASH_UUID
                alterId: 64
            """.trimIndent()
        (parseClashYaml(yaml).profiles.single().outbound as VmessOutbound).alterId shouldBe 64
    }

    @Test
    fun `a malformed uuid is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Tokyo"
                type: vmess
                server: a.example
                port: 443
                uuid: not-a-uuid
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `a missing trojan password is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Berlin"
                type: trojan
                server: b.example
                port: 443
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.MissingCredential
    }

    @Test
    fun `an unsupported shadowsocks cipher is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Oslo"
                type: ss
                server: c.example
                port: 8388
                cipher: rc4-md5
                password: s3cret
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.UnsupportedMethod
    }

    @Test
    fun `a missing server is a typed failure`() {
        val yaml =
            """
            proxies:
              - name: "Nowhere"
                type: trojan
                port: 443
                password: s3cret
            """.trimIndent()
        parseClashYaml(yaml).failures.single().reason shouldBe ParseFailureReason.MalformedYaml
    }

    /**
     * The same server in two containers is one server. Both branches must build
     * the profile id from identical material, or the same entry imported from a
     * Clash file and from a link list becomes two profiles.
     */
    @Test
    fun `a trojan proxy gets the same id as the equivalent link`() {
        val link = "trojan://s3cret@b.example:443#Berlin"
        val fromLink = (parseShareLink(link, 0) as LinkResult.Ok).profile
        parseClashYaml(config).profiles[1].id shouldBe fromLink.id
    }

    @Test
    fun `a shadowsocks proxy gets the same id as the equivalent link`() {
        val link = "ss://YWVzLTI1Ni1nY206czNjcmV0@c.example:8388#Oslo"
        val fromLink = (parseShareLink(link, 0) as LinkResult.Ok).profile
        parseClashYaml(config).profiles[2].id shouldBe fromLink.id
    }

    @Test
    fun `a vmess proxy gets the same id as the equivalent link`() {
        val fromLink = (parseShareLink(VMESS_LINK, 0) as LinkResult.Ok).profile
        parseClashYaml(config).profiles[0].id shouldBe fromLink.id
    }

    /**
     * §5.6: a ParseFailure is a diagnostic and diagnostics reach logs. Nothing
     * from the config may appear in one, and the Clash branch never quotes the
     * document.
     */
    @Test
    fun `failures never echo the server or the credential`() {
        val yaml =
            """
            proxies:
              - name: "Leaky"
                type: trojan
                server: secret.example.com
                port: 70000
                password: hunter2
            """.trimIndent()
        val detail = parseClashYaml(yaml).failures.single().detail
        detail.contains("secret.example.com") shouldBe false
        detail.contains("hunter2") shouldBe false
        detail.contains("70000") shouldBe true
    }

    /**
     * §7's rule for this branch specifically. kaml only translates the engine's
     * *marked* exceptions, so anything reaching the parser unwrapped would
     * escape a narrower catch.
     */
    @Test
    fun `never throws on nasty yaml`() {
        val nasty =
            listOf(
                "",
                "   ",
                "\n\n",
                "proxies:",
                "proxies: null",
                "proxies: []",
                "proxies: notalist",
                "proxies:\n  - [",
                "proxies:\n  - {",
                "proxies:\n  -",
                "proxies:\n  - name: a\n    name: a",
                "proxies:\n  - *alias",
                "proxies:\n  - &a\n  - *a",
                "\ttabs: are: illegal",
                "%YAML 1.2\n---\nproxies: []",
                // Load-bearing: kaml lets snakeyaml-engine's YamlVersionException
                // through untranslated, and it is not a YamlException. This entry
                // fails if parseYamlMap's catch is narrowed to YamlException.
                "%YAML 2.0\n---\nproxies: []",
                "@invalid",
                "`backtick",
                "😀: 😀",
                "{",
                "{}",
                "proxies: {a: b}",
            )
        nasty.forEach { input ->
            withClue("input: ${input.take(32)}") {
                shouldNotThrowAny { parseClashYaml(input) }
            }
        }
    }
}
