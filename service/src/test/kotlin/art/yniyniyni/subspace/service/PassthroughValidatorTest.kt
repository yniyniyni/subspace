// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PassthroughValidatorTest {
    // A fake core: the real one needs a device, and this test is about what the
    // validator *composes and asks*, not about xray-core's verdict. Task 5 covers
    // the verdict on hardware.
    private class RecordingCore(val accept: Boolean) {
        var lastConfig: String? = null

        suspend fun validate(json: String): Boolean {
            lastConfig = json
            return accept
        }
    }

    private val ordinary =
        """{ "outbounds": [ { "tag": "proxy", "protocol": "vless" } ] }"""

    @Test
    fun `composes against the asset directory it was given, not a hardcoded one`() =
        runTest {
            val core = RecordingCore(accept = true)
            val validator = BoundPassthroughValidator(assetDir = { "/data/user/0/pkg/files/geo" }) { json ->
                core.validate(json)
            }

            validator.validate(ordinary) shouldBe true

            val sent = core.lastConfig!!
            // §10.5, measured 2026-08-31: the composed env is the ONLY channel
            // testXray reads xray.location.asset from — the libXray invoke
            // envelope does not override it. A placeholder here refuses every
            // config carrying a geosite:/geoip: rule.
            sent.contains(""""xray.location.asset":"/data/user/0/pkg/files/geo"""") shouldBe true
            sent.contains("/data/local/tmp") shouldBe false
        }

    @Test
    fun `composes before validating, so the bytes tested are the bytes run`() =
        runTest {
            val core = RecordingCore(accept = true)
            val validator = BoundPassthroughValidator(assetDir = { "/geo" }, testConfig = core::validate)

            validator.validate(ordinary) shouldBe true

            val sent = core.lastConfig!!
            // The composed form, not the stored form: our inbound pair is present.
            // The `inbounds` block is Inbounds.kt's own hand-formatted text (spaced), while
            // `log` is rendered from a kotlinx.serialization JsonElement (compact, no space
            // after `:`) — see RawConfigComposer.render and RawConfigComposerTest's own
            // structural (not substring) assertion on this same field.
            sent.contains(""""tag": "socks-in"""") shouldBe true
            sent.contains(""""access":"none"""") shouldBe true
        }

    @Test
    fun `a config the core refuses is not eligible`() =
        runTest {
            val validator =
                BoundPassthroughValidator(assetDir = { "/geo" }, testConfig = RecordingCore(accept = false)::validate)

            validator.validate(ordinary) shouldBe false
        }

    @Test
    fun `a config the composer cannot build is not eligible, and the core is never asked`() =
        runTest {
            val core = RecordingCore(accept = true)
            val validator = BoundPassthroughValidator(assetDir = { "/geo" }, testConfig = core::validate)

            validator.validate("not json") shouldBe false

            core.lastConfig shouldBe null
        }

    // Review Important 2: testConfig throwing anything other than the core's own refusal (which
    // it reports by returning false, never by throwing) means the check could not run at all —
    // a full cache failing the temp-file write, say. That is "undetermined", not "rejected": a
    // permanent CoreRejected for a transient failure is exactly the §10.4 lie this guards against.
    @Test
    fun `a failure to even run the check is undetermined, not a rejection`() =
        runTest {
            val validator =
                BoundPassthroughValidator(assetDir = { "/geo" }) { throw java.io.IOException("cache full") }

            validator.validate(ordinary) shouldBe true
        }

    // Review Important 4: the old `runCatching { testConfig(...) }.getOrDefault(false)` turned a
    // cancelled import into a recorded rejection. Cancellation must propagate, never be reported
    // as any kind of verdict.
    @Test
    fun `cancellation propagates rather than being reported as a verdict`() =
        runTest {
            val validator =
                BoundPassthroughValidator(assetDir = { "/geo" }) { throw CancellationException("navigated away") }

            shouldThrow<CancellationException> { validator.validate(ordinary) }
        }
}
