// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.parser.DetailField
import art.yniyniyni.subspace.core.parser.FailureDetail
import art.yniyniyni.subspace.core.parser.ParseFailureReason
import art.yniyniyni.subspace.core.parser.ParseOutcome
import art.yniyniyni.subspace.core.parser.parseFailure
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Covers `ShareLinkFallback`'s control flow — which guard suppresses a call,
 * and when — using the internal `retry(originalText, outcome, convert)` seam
 * so a spy converter can prove a call was or was not made.
 *
 * Asserting on [ShareLinkFallback.retry]'s *return value* alone cannot tell
 * the container-shape skip apart from the pre-existing `"://"` line guard:
 * with the skip removed, a JSON/YAML-shaped fixture whose failing line lacks
 * `"://"` still keeps its failure and returns an outcome identical to the
 * skip-present case, because the *other* guard also blocks it — that fixture
 * would pass with the skip deleted, proving nothing. So each fixture below
 * places a `"://"`-bearing value exactly at the failure's index, which is
 * what actually exercises the container-shape skip: with it removed, the
 * per-line guard alone would let the call through.
 *
 * The rest of `ShareLinkFallback` — the real libXray call and the
 * recover-or-degrade decision built on its result — needs the AAR and stays
 * covered only by `ShareLinkFallbackTest` in `androidTest`.
 */
class ShareLinkFallbackDetectionTest {
    @Test
    fun `does not call the converter for JSON-shaped input even when the failing line contains a scheme`() {
        val text = "{\n  \"outbounds\": [\n    { \"sni\": \"https://evil.example\" }\n  ]\n}"
        val failure =
            parseFailure(
                2,
                ParseFailureReason.UnknownScheme,
                FailureDetail.Unsupported(DetailField.Scheme),
            )
        val outcome = ParseOutcome(emptyList(), listOf(failure))
        val calls = mutableListOf<String>()

        ShareLinkFallback.retry(text, outcome) { line ->
            calls += line
            null
        }

        calls shouldBe emptyList()
    }

    @Test
    fun `does not call the converter for Clash-YAML-shaped input even when the failing line contains a scheme`() {
        val text = "proxies:\n  - name: foo\n    providers: https://evil.example/list"
        val failure =
            parseFailure(
                2,
                ParseFailureReason.UnknownScheme,
                FailureDetail.Unsupported(DetailField.Scheme),
            )
        val outcome = ParseOutcome(emptyList(), listOf(failure))
        val calls = mutableListOf<String>()

        ShareLinkFallback.retry(text, outcome) { line ->
            calls += line
            null
        }

        calls shouldBe emptyList()
    }

    @Test
    fun `does call the converter for a link-list failing line that contains a scheme`() {
        val text = "wireguard://nonsense@host.example:443"
        val failure =
            parseFailure(
                0,
                ParseFailureReason.UnknownScheme,
                FailureDetail.Unsupported(DetailField.Scheme),
            )
        val outcome = ParseOutcome(emptyList(), listOf(failure))
        val calls = mutableListOf<String>()

        ShareLinkFallback.retry(text, outcome) { line ->
            calls += line
            null
        }

        calls shouldBe listOf(text)
    }

    @Test
    fun `never calls the converter for a clean outcome`() {
        val outcome = ParseOutcome(emptyList(), emptyList())
        val calls = mutableListOf<String>()

        ShareLinkFallback.retry("vless://host.example", outcome) { line ->
            calls += line
            null
        }

        calls shouldBe emptyList()
    }
}
