// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.parser.ParseFailureReason
import art.yniyniyni.subspace.core.parser.ParseOutcome
import art.yniyniyni.subspace.core.parser.parseFailure
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Covers only the container-shape skip in [ShareLinkFallback.retry] — the part
 * that runs before any libXray call. The rest of `ShareLinkFallback` needs the
 * real AAR and is verified by `ShareLinkFallbackTest` in `androidTest`
 * instead; see that file's KDoc for why this split exists.
 *
 * These fixtures never reach [ShareLinkFallback]'s JNI call: on the JVM,
 * `org.json.JSONObject` is an unmocked Android stub that throws at runtime, so
 * any test that exercised `convertOrNull` would need `androidTest`. The two
 * cases here return before that point.
 */
class ShareLinkFallbackDetectionTest {
    @Test
    fun `skips retry entirely for raw Xray JSON input`() {
        val text = "{\n  \"outbounds\": [\n    { \"protocol\": \"vless\" }\n  ]\n}"
        val outcome =
            ParseOutcome(
                emptyList(),
                listOf(parseFailure(0, ParseFailureReason.UnknownScheme, "outbound protocol is not supported")),
            )

        ShareLinkFallback.retry(text, outcome) shouldBe outcome
    }

    @Test
    fun `skips retry entirely for Clash YAML input`() {
        val text = "proxies:\n  - name: foo\n    type: made-up\n    server: host.example\n    port: 443"
        val outcome =
            ParseOutcome(
                emptyList(),
                listOf(parseFailure(0, ParseFailureReason.UnknownScheme, "proxy type is not supported")),
            )

        ShareLinkFallback.retry(text, outcome) shouldBe outcome
    }

    @Test
    fun `still returns a clean outcome untouched`() {
        val outcome = ParseOutcome(emptyList(), emptyList())

        ShareLinkFallback.retry("vless://host.example", outcome) shouldBe outcome
    }
}
