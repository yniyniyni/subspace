// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

private const val SUB_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

class SubscriptionParserTest {
    @Test
    fun `detects a plain link`() {
        SubscriptionParser.parse("vless://$SUB_UUID@a.example:443").profiles.size shouldBe 1
    }

    @Test
    fun `detects a base64 subscription blob`() {
        val plain = "vless://$SUB_UUID@a.example:443\ntrojan://pw@b.example:443"
        val blob = Base64.getEncoder().encodeToString(plain.toByteArray())
        SubscriptionParser.parse(blob).profiles.size shouldBe 2
    }

    @Test
    fun `detects clash yaml`() {
        val yaml =
            """
            proxies:
              - name: "T"
                type: trojan
                server: a.example
                port: 443
                password: pw
            """.trimIndent()
        SubscriptionParser.parse(yaml).profiles.size shouldBe 1
    }

    @Test
    fun `detects raw xray json`() {
        val json =
            """
            {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[
            {"address":"a.example","port":443,"users":[{"id":"$SUB_UUID"}]}]}}]}
            """.trimIndent()
        SubscriptionParser.parse(json).profiles.size shouldBe 1
    }

    /**
     * Load-bearing, not stylistic. JSON is a subset of YAML, so a raw Xray
     * config parses cleanly as YAML. If the YAML check ran first, every raw
     * config would be routed to the Clash branch, fail on the missing
     * `proxies:` key, and be reported as malformed YAML — a diagnosis pointing
     * at entirely the wrong format.
     */
    @Test
    fun `raw xray json is not routed to the clash branch`() {
        val json =
            """
            {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[
            {"address":"a.example","port":443,"users":[{"id":"$SUB_UUID"}]}]}}]}
            """.trimIndent()
        val outcome = SubscriptionParser.parse(json)
        outcome.failures.none { it.reason == ParseFailureReason.MalformedYaml } shouldBe true
    }

    @Test
    fun `empty input is a single EmptyInput failure`() {
        val outcome = SubscriptionParser.parse("   \n  ")
        outcome.profiles.size shouldBe 0
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.EmptyInput
    }

    /**
     * The decode succeeds, so the fallback is not "this is not valid base64"
     * (that would be a lie) — it is "this decoded fine but is not links",
     * which the re-entered parse discovers by actually trying the decoded
     * text as a link list.
     */
    @Test
    fun `a base64 blob that decodes to non-link text reports unknown scheme`() {
        val plain = "This is just some ordinary text, not a link or a config of any kind"
        val blob = Base64.getEncoder().encodeToString(plain.toByteArray())
        val outcome = SubscriptionParser.parse(blob)
        outcome.profiles.size shouldBe 0
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.UnknownScheme
    }

    @Test
    fun `a corrupt base64 blob reports MalformedBase64, not UnknownScheme`() {
        val corrupt = "%%%invalidBase64DataThatWontDecode%%%"
        val outcome = SubscriptionParser.parse(corrupt)
        outcome.profiles.size shouldBe 0
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedBase64
    }

    @Test
    fun `a base64 blob that decodes to clash yaml is parsed via re-entry`() {
        val yaml =
            """
            proxies:
              - name: "T"
                type: trojan
                server: a.example
                port: 443
                password: pw
            """.trimIndent()
        val blob = Base64.getEncoder().encodeToString(yaml.toByteArray())
        SubscriptionParser.parse(blob).profiles.size shouldBe 1
    }

    @Test
    fun `a base64 blob that decodes to raw xray json is parsed via re-entry`() {
        val json =
            """
            {"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[
            {"address":"a.example","port":443,"users":[{"id":"$SUB_UUID"}]}]}}]}
            """.trimIndent()
        val blob = Base64.getEncoder().encodeToString(json.toByteArray())
        SubscriptionParser.parse(blob).profiles.size shouldBe 1
    }

    @Test
    fun `a plain multi-line link list is still parsed as links`() {
        val list = "vless://$SUB_UUID@a.example:443\ntrojan://pw@b.example:443"
        SubscriptionParser.parse(list).profiles.size shouldBe 2
    }
}
