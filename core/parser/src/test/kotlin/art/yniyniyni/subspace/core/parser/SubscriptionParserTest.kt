// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
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
     * Defect 1 (device fixes report): the target panel (Remnawave) returns a
     * top-level JSON **array** wrapping a whole Xray config when asked with a
     * v2rayNG/Happ User-Agent — `[{ "dns": …, "outbounds": … }]`. Before this
     * fix `text.startsWith("{")` was `false` for `[`, so this fell through to
     * the Clash check, then base64, and failed with "Imported 0 of 1" instead
     * of reaching [parseXrayJson] at all.
     */
    @Test
    fun `detects a top-level json array wrapping a raw xray config`() {
        val json =
            """
            [{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[
            {"address":"a.example","port":443,"users":[{"id":"$SUB_UUID"}]}]}}]}]
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

    /**
     * §10.4: the redacted failure detail is the only diagnostic a user can hand
     * back, and `HomeViewModel` shows the generic "could not parse" message with
     * *no* detail when `failures` is empty. Every container shape below is
     * recognised, well-formed, and simply holds nothing — the individual format
     * parsers are each locally right to return no failure, so the invariant is
     * enforced once in [SubscriptionParser.parse] instead.
     */
    @Test
    fun `a container that yields nothing still reports a failure`() {
        val emptyContainers =
            listOf(
                "{}",
                """{"foo":1}""",
                """{"outbounds":[]}""",
                "proxies:",
                "proxies: null",
                "proxies: []",
            )

        emptyContainers.forEach { input ->
            withClue("input: $input") {
                val outcome = SubscriptionParser.parse(input)
                outcome.profiles.shouldBeEmpty()
                outcome.failures.shouldNotBeEmpty()
            }
        }
    }

    @Test
    fun `an empty container names the reason rather than saying nothing`() {
        val outcome = SubscriptionParser.parse("{}")
        outcome.failures.single().reason shouldBe ParseFailureReason.EmptyInput
    }
}
