// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class ValidationTest {
    @Test
    fun `accepts valid port`() {
        validatePort(443) shouldBe null
    }

    @Test
    fun `rejects port zero and out of range`() {
        validatePort(0) shouldBe FailureDetail.Range(DetailField.Port, 1, 65_535, 0)
        validatePort(70_000) shouldBe FailureDetail.Range(DetailField.Port, 1, 65_535, 70_000)
    }

    @Test
    fun `accepts a well formed uuid`() {
        validateUuid("70cc48c5-b2f4-4a1e-9f3d-0123456789ab") shouldBe null
    }

    @Test
    fun `rejects a malformed uuid`() {
        validateUuid("not-a-uuid") shouldBe FailureDetail.Malformed(DetailField.Uuid)
    }

    @Test
    fun `reports a missing uuid separately from a malformed one`() {
        validateUuid("") shouldBe FailureDetail.Missing(DetailField.Uuid)
    }

    @Test
    fun `accepts a 43 char unpadded base64url reality key`() {
        validateRealityPublicKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8") shouldBe null
    }

    @Test
    fun `rejects a non canonical reality key with non zero pad bits`() {
        validateRealityPublicKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh9") shouldBe
            FailureDetail.Malformed(DetailField.PublicKey)
    }

    @Test
    fun `rejects a truncated reality key`() {
        validateRealityPublicKey("AAECAwQF") shouldBe
            FailureDetail.Length(DetailField.PublicKey, expected = 43, actual = 8)
    }

    @Test
    fun `missing reality key names the public key field`() {
        validateRealityPublicKey("") shouldBe FailureDetail.Missing(DetailField.PublicKey)
    }

    @Test
    fun `accepts every method in the supported set`() {
        val methods =
            setOf(
                "aes-128-gcm",
                "aes-192-gcm",
                "aes-256-gcm",
                "chacha20-ietf-poly1305",
                "xchacha20-ietf-poly1305",
                "2022-blake3-aes-128-gcm",
                "2022-blake3-aes-256-gcm",
                "2022-blake3-chacha20-poly1305",
                "none",
                "plain",
            )

        methods shouldBe SHADOWSOCKS_METHODS
        methods.forEach { validateShadowsocksMethod(it) shouldBe null }
    }

    @Test
    fun `rejects unknown shadowsocks method without echoing it`() {
        validateShadowsocksMethod("rot13") shouldBe FailureDetail.Unsupported(DetailField.Method)
    }

    @Test
    fun `arbitrary strings never throw and produce only typed details`() {
        val inputs = listOf("", "\u0000", "\n", "x".repeat(10_000), "credential\uD83D\uDD12")
        inputs.forEach { input ->
            validateUuid(input) shouldNotBe null
            validateRealityPublicKey(input) shouldNotBe null
            validateShadowsocksMethod(input) shouldNotBe null
        }
    }
}
