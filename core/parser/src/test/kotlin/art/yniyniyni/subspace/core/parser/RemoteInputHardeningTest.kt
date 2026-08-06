// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

/**
 * M2 residuals that only become reachable once M4 fetches bodies over the
 * network. Both are recorded in
 * `docs/agent/research/2026-07-27-m2-residuals-for-m3.md` §6.
 */
class RemoteInputHardeningTest {
    private val bom = "\uFEFF"

    @Test
    fun `a UTF-8 BOM does not defeat JSON detection`() {
        val config = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[]}}]}"""
        val withBom = bom + config

        val plain = SubscriptionParser.parse(config)
        val bommed = SubscriptionParser.parse(withBom)

        // Not asserting success — the fixture may legitimately yield a failure.
        // Asserting the BOM changes nothing, which is the actual requirement.
        bommed.failures.map { it.reason } shouldBe plain.failures.map { it.reason }
        bommed.profiles.size shouldBe plain.profiles.size
    }

    @Test
    fun `a BOM before a share link list still parses the links`() {
        // pbk must be a canonical 43-char base64url reality key (Validation.kt's
        // REALITY_KEY_LENGTH) or the entry fails on InvalidRealityKey regardless
        // of BOM handling — this value matches VlessLinkTest's PBK fixture.
        val body =
            bom + "vless://11111111-2222-3333-4444-555555555555@example.com:443?" +
                "security=reality&pbk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&sni=example.org&fp=chrome#Server"

        SubscriptionParser.parse(body).profiles.size shouldBe 1
    }

    @Test
    fun `a BOM before Clash YAML still routes to the Clash branch`() {
        val yaml = bom + "proxies:\n  - {name: a, type: vless, server: h, port: 443, uuid: u}"

        // The failure, if any, must not be "malformed base64" — that would mean
        // detection sent it down the wrong branch entirely (§10.4).
        SubscriptionParser.parse(yaml).failures.forEach {
            it.reason shouldNotBe ParseFailureReason.MalformedBase64
        }
    }

    @Test
    fun `a pathologically nested JSON body fails as a ParseFailure, not a StackOverflowError`() {
        val depth = 100_000
        val nested = "[".repeat(depth) + "]".repeat(depth)

        val outcome = SubscriptionParser.parse(nested)

        outcome.profiles.isEmpty() shouldBe true
        outcome.failures.isNotEmpty() shouldBe true
    }

    @Test
    fun `a pathologically nested YAML body fails as a ParseFailure`() {
        val depth = 100_000
        val nested = "proxies:\n" + "  ".repeat(0) + "- ".repeat(depth) + "x"

        val outcome = SubscriptionParser.parse(nested)

        outcome.failures.isNotEmpty() shouldBe true
    }
}
