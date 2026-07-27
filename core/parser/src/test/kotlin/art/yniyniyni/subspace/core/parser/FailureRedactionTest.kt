// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * §5.6: server addresses, UUIDs, REALITY keys and subscription URLs are
 * secrets. A ParseFailure is a diagnostic, and diagnostics reach logs, crash
 * reports, and the in-app log viewer.
 *
 * Mirrors RedactionTest in :core:model, but at the boundary where user input
 * actually enters the system.
 */
class FailureRedactionTest {
    private val secretHost = "secret.example.com"
    private val secretUuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
    private val secretKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    private val badInputs =
        listOf(
            "vless://$secretUuid@$secretHost:70000",
            "vless://$secretUuid@$secretHost:443?security=reality&pbk=AAEC",
            "trojan://@$secretHost:443",
            "ss://cm90MTM6cHc@$secretHost:8388",
            "wireguard://$secretUuid@$secretHost:443",
        )

    @Test
    fun `no failure detail leaks a host, uuid, or key`() {
        badInputs.forEach { input ->
            val outcome = SubscriptionParser.parse(input)
            outcome.failures.forEach { failure ->
                failure.detail.contains(secretHost) shouldBe false
                failure.detail.contains(secretUuid) shouldBe false
                failure.detail.contains(secretKey) shouldBe false
            }
        }
    }

    /**
     * The two host shapes that match none of `redact`'s shape-based patterns.
     * M2 made IPv6 a first-class parse target, and a single-label host — an
     * internal name with no dot in it — is indistinguishable from an ordinary
     * word without looking at the position it sits in.
     */
    @Test
    fun `no failure detail leaks an ipv6 or single-label host`() {
        val ipv6Host = "2001:db8::1"
        val singleLabelHost = "vpnserver"
        val inputs =
            listOf(
                "vless://$secretUuid@[$ipv6Host]:70000",
                "trojan://@[$ipv6Host]:443",
                "vless://$secretUuid@$singleLabelHost:70000",
                "trojan://@$singleLabelHost:443",
            )

        inputs.forEach { input ->
            val outcome = SubscriptionParser.parse(input)
            outcome.failures.forEach { failure ->
                withClue(input) {
                    failure.detail.contains(ipv6Host) shouldBe false
                    failure.detail.contains(singleLabelHost) shouldBe false
                }
            }
        }
    }

    /**
     * Redaction must not make failures useless. The port number is not a
     * secret and is the whole diagnostic.
     */
    @Test
    fun `redaction leaves the actionable part intact`() {
        val outcome = SubscriptionParser.parse("vless://$secretUuid@$secretHost:70000")
        val detail = outcome.failures.single().detail
        detail.contains("70000") shouldBe true
    }
}
