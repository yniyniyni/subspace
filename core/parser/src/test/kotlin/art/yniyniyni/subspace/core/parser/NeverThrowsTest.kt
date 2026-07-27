// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.random.Random

/**
 * ARCHITECTURE.md §7's central rule, as a test rather than a comment:
 * **never throw on malformed input.**
 *
 * A parser that throws on one entry loses the whole subscription, and the input
 * is arbitrary text a user pasted or a server returned. This is the test that
 * fails when someone adds a `.first()`, a `!!`, or a `toInt()` in a hurry.
 */
class NeverThrowsTest {
    private val nasty =
        listOf(
            "",
            " ",
            "\n\n\n",
            "\u0000",
            "vless://",
            "vless://@",
            "vless://@:",
            "vmess://",
            "ss://",
            "ss://@@@",
            "trojan://",
            "socks://",
            "://nohost",
            "vless://u@h:notaport",
            "vless://u@h:-1",
            "vless://u@h:999999999999999999999",
            "{",
            "{}",
            """{"outbounds":null}""",
            """{"outbounds":[{}]}""",
            "proxies:",
            "proxies: notalist",
            "proxies:\n  - [",
            "😀😀😀",
            "%%%%%%",
            "vless://u@h:443#%ZZ",
            "aGVsbG8",
        )

    @Test
    fun `never throws on known-nasty input`() {
        nasty.forEach { input ->
            // withClue names the offending input in the failure message —
            // without it, a failure here says only "an exception was thrown"
            // and the 27 candidates all look alike.
            withClue("input: ${input.take(32)}") {
                shouldNotThrowAny { SubscriptionParser.parse(input) }
            }
        }
    }

    @Test
    fun `never throws on random bytes`() {
        // Fixed seed: a fuzz test that cannot be reproduced is a flake
        // generator, not a regression test.
        val random = Random(seed = 20260726)
        repeat(500) { iteration ->
            val bytes = ByteArray(random.nextInt(0, 512)) { random.nextInt(-128, 128).toByte() }
            val input = String(bytes, Charsets.UTF_8)
            withClue("iteration $iteration") {
                shouldNotThrowAny { SubscriptionParser.parse(input) }
            }
        }
    }

    @Test
    fun `never throws on a very large input`() {
        val big = "vless://70cc48c5-b2f4-4a1e-9f3d-0123456789ab@h.example:443\n".repeat(20_000)
        SubscriptionParser.parse(big).profiles.size shouldBe 20_000
    }

    @Test
    fun `never throws on deeply nested json`() {
        val deep = "{\"a\":".repeat(200) + "1" + "}".repeat(200)
        shouldNotThrowAny { SubscriptionParser.parse(deep) }
    }
}
