// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    @Test
    fun `profile ids distinguish credentials with colliding string hashes`() {
        val first = profileId("socks", "host.example", 1080, "Aa")
        val second = profileId("socks", "host.example", 1080, "BB")

        first shouldNotBe second
    }

    @Test
    fun `profile ids are stable for identical input`() {
        val first = profileId("socks", "host.example", 1080, "secret")
        val second = profileId("socks", "host.example", 1080, "secret")

        first shouldBe second
    }

    // No runtime test pins "ParseFailure's constructor and copy() are private"
    // — that guarantee is enforced by the compiler, not at runtime, and this
    // module has no kotlin-reflect dependency to introspect visibility with
    // (it's on the test *runtime* classpath transitively via kotest, but not
    // the compile classpath — adding it as a real dependency for one test
    // would be the exact kind of speculative addition ARCHITECTURE.md §10.7
    // rules out). The guarantee was instead verified empirically with a
    // throwaway file compiled from outside this class and deleted before
    // commit. See the Task 3 fix report for the two compiler errors it
    // produced.
}
