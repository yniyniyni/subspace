// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {
    @Test
    fun `accepts valid port`() {
        validatePort(443) shouldBe null
    }

    @Test
    fun `rejects port zero and out of range`() {
        (validatePort(0) != null) shouldBe true
        (validatePort(70000) != null) shouldBe true
    }

    @Test
    fun `accepts a well formed uuid`() {
        validateUuid("70cc48c5-b2f4-4a1e-9f3d-0123456789ab") shouldBe null
    }

    @Test
    fun `rejects a malformed uuid`() {
        val input = "not-a-uuid"
        val message = validateUuid(input)
        assertTrue(message != null)
        assertFalse(message.orEmpty().contains(input))
    }

    @Test
    fun `accepts a 43 char unpadded base64url reality key`() {
        validateRealityPublicKey("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8") shouldBe null
    }

    @Test
    fun `rejects a truncated reality key`() {
        val input = "AAECAwQF"
        val message = validateRealityPublicKey(input)
        assertTrue(message != null)
        assertFalse(message.orEmpty().contains(input))
    }

    @Test
    fun `reality key message names publicKey not password`() {
        validateRealityPublicKey("AAECAwQF") shouldContain "publicKey"
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
        val input = "rot13"
        val message = validateShadowsocksMethod(input)
        assertTrue(message != null)
        assertFalse(message.orEmpty().contains(input))
    }

    @Test
    fun `arbitrary strings never throw and are not echoed`() {
        val inputs = listOf("", "\u0000", "\n", "x".repeat(10_000), "credential\uD83D\uDD12")
        inputs.forEach { input ->
            val messages =
                listOf(
                    validateUuid(input),
                    validateRealityPublicKey(input),
                    validateShadowsocksMethod(input),
                )
            messages.filterNotNull().forEach { message ->
                if (input.isNotEmpty()) {
                    assertFalse(message.contains(input))
                }
            }
        }
    }
}
