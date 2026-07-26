// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class ParseOutcomeTest {
    @Test
    fun `failure detail is redacted at construction`() {
        val f = parseFailure(0, ParseFailureReason.MalformedUri, "bad host secret.example.com")
        f.detail shouldNotContain "secret.example.com"
    }

    @Test
    fun `failure detail redacts uuids`() {
        val uuid = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
        parseFailure(1, ParseFailureReason.MissingCredential, "uuid $uuid").detail shouldNotContain uuid
    }

    @Test
    fun `index and reason survive redaction`() {
        val f = parseFailure(143, ParseFailureReason.InvalidPort, "port must be 1..65535, got 70000")
        f.index shouldBe 143
        f.reason shouldBe ParseFailureReason.InvalidPort
        // The number is not a secret and must remain useful.
        f.detail shouldBe "port must be 1..65535, got 70000"
    }

    @Test
    fun `outcomes combine`() {
        val failure = parseFailure(0, ParseFailureReason.EmptyInput, "empty")
        val a = ParseOutcome(profiles = emptyList(), failures = listOf(failure))
        val b = ParseOutcome.EMPTY
        (a + b).failures.size shouldBe 1
    }
}
