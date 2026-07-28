// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * §5.6: server addresses, UUIDs, REALITY keys and subscription URLs are
 * secrets. A ParseFailure is a diagnostic, and diagnostics reach logs, crash
 * reports, and the in-app log viewer.
 *
 * Unlike the separate model redaction tests, parser diagnostics do not accept
 * prose at all. These tests pin the typed meanings and structurally verify
 * there is no String field through which config text could travel.
 */
class FailureRedactionTest {
    private val secretHost = "secret.example.com"
    private val secretUuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

    private val badInputs =
        listOf(
            "vless://$secretUuid@$secretHost:70000",
            "vless://$secretUuid@$secretHost:443?security=reality&pbk=AAEC",
            "trojan://@$secretHost:443",
            "ss://cm90MTM6cHc@$secretHost:8388",
            "wireguard://$secretUuid@$secretHost:443",
        )

    @Test
    fun `parser failures expose typed meanings without config values`() {
        val expected =
            listOf(
                FailureDetail.Range(DetailField.Port, 1, 65_535, 70_000),
                FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 4),
                FailureDetail.Missing(DetailField.Password),
                FailureDetail.Unsupported(DetailField.Method),
                FailureDetail.Unsupported(DetailField.Scheme),
            )

        val actual =
            badInputs.map { input ->
                val outcome = SubscriptionParser.parse(input)
                outcome.failures.single().detail
            }
        actual shouldBe expected
    }

    @Test
    fun `failure detail graph has no free-text field`() {
        ParseFailure::class.java.declaredFields.none { it.type == String::class.java } shouldBe true
        FailureDetail::class.java.isSealed shouldBe true
        FailureDetail::class.java.permittedSubclasses
            .flatMap { it.declaredFields.asList() }
            .none { it.type == String::class.java } shouldBe true
    }
}
