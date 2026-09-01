// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.parser

import io.kotest.matchers.shouldBe
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
        val yaml =
            bom + "proxies:\n  - {name: a, type: vless, server: example.com, port: 443, " +
                "uuid: 11111111-1111-1111-1111-111111111111}"

        // Assert the positive outcome, not the absence of one wrong reason. The previous version
        // of this test asserted only `reason != MalformedBase64` over `failures`, which could not
        // fail: with the BOM strip deleted, `looksLikeClash` misses, the text is not JSON, base64
        // decoding returns null, and `looksLikeBlob` is false because the fixture contains
        // whitespace — so it lands in `parseLinkList`, which never emits MalformedBase64 at all.
        // The one reason it excluded was the one reason the broken path could not produce, and
        // `forEach` over an empty list asserts nothing either way.
        SubscriptionParser.parse(yaml).profiles.size shouldBe 1
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
