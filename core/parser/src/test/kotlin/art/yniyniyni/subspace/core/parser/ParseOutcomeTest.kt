// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class ParseOutcomeTest {
    @Test
    fun `failure preserves index reason and typed detail`() {
        val detail = FailureDetail.Range(DetailField.Port, 1, 65_535, 70_000)
        val f = parseFailure(143, ParseFailureReason.InvalidPort, detail)

        f.index shouldBe 143
        f.reason shouldBe ParseFailureReason.InvalidPort
        f.detail shouldBe detail
    }

    @Test
    fun `outcomes combine`() {
        val failure = parseFailure(0, ParseFailureReason.EmptyInput, FailureDetail.None)
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
