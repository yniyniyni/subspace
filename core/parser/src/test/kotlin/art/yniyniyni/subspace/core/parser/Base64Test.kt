// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

/**
 * §7: real subscriptions violate their own specs constantly. Every case here is
 * a shape seen in the wild, not a hypothetical.
 */
class Base64Test {
    @Test
    fun `decodes standard base64`() {
        decodeBase64Tolerant("aGVsbG8=") shouldBe "hello"
    }

    @Test
    fun `decodes without padding`() {
        decodeBase64Tolerant("aGVsbG8") shouldBe "hello"
    }

    @Test
    fun `decodes url-safe alphabet`() {
        // "~~~?" in standard base64 is fn5+Pw== ; url-safe uses - and _.
        // Verified independently: fn5-Pw -> fn5+Pw -> pad to fn5+Pw== -> ~~~?
        decodeBase64Tolerant("fn5-Pw") shouldBe "~~~?"
    }

    @Test
    fun `ignores embedded whitespace and newlines`() {
        decodeBase64Tolerant("aGVs\nbG8=\n") shouldBe "hello"
    }

    @Test
    fun `returns null rather than throwing on garbage`() {
        decodeBase64Tolerant("!!!not base64!!!") shouldBe null
    }

    @Test
    fun `returns null on empty`() {
        decodeBase64Tolerant("") shouldBe null
    }

    @Test
    fun `does not throw on invalid utf8 bytes`() {
        // 0xFF 0xFE is not valid UTF-8; decoding must degrade, not throw.
        val input = Base64.getEncoder().encodeToString(byteArrayOf(-1, -2))
        decodeBase64Tolerant(input) // must not throw
    }
}
