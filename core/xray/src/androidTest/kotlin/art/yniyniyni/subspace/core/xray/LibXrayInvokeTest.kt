// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.xray

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the envelope rule that keeps a fake latency off the screen.
 *
 * libXray never throws — a failure arrives inside the response envelope — and
 * for `ping` that envelope carries **both** `success:false` and a sentinel
 * `delay` of `10000` (`PingDelayError`) or `11000` (`PingDelayTimeout`).
 * Discarding `data` on failure is what makes those unreachable, so that a failed
 * measurement can never be rendered as "10000 ms" (§10.1).
 *
 * Instrumented rather than a JVM unit test because `org.json` is a stub in
 * `android.jar`: calling `JSONObject.optBoolean` off-device throws
 * "not mocked". The alternative — a JSON dependency added purely for tests, or a
 * project-wide `returnDefaultValues` that silently defaults *every* unmocked
 * Android call — costs more than running this next to the module's other
 * real-libXray checks.
 */
@RunWith(AndroidJUnit4::class)
class LibXrayInvokeTest {
    @Test
    fun aFailureEnvelopeCarryingASentinelDelayThrowsInsteadOfReturningIt() {
        val envelope = """{"success":false,"data":{"delay":10000},"error":"connection refused"}"""

        val thrown = runCatching { LibXrayInvoke.parse("ping", envelope) }.exceptionOrNull()

        (thrown is XrayException) shouldBe true
    }

    @Test
    fun theTimeoutSentinelIsDiscardedOnTheSamePath() {
        val envelope = """{"success":false,"data":{"delay":11000},"error":"timeout"}"""

        val thrown = runCatching { LibXrayInvoke.parse("ping", envelope) }.exceptionOrNull()

        (thrown is XrayException) shouldBe true
    }

    @Test
    fun aSuccessfulEnvelopeReturnsItsData() {
        val data = LibXrayInvoke.parse("ping", """{"success":true,"data":{"delay":42},"error":""}""")

        data?.optInt("delay") shouldBe 42
    }

    @Test
    fun aMethodReturningNoDataSucceedsWithNullRatherThanThrowing() {
        LibXrayInvoke.parse("stopXray", """{"success":true,"data":null,"error":""}""").shouldBeNull()
    }

    @Test
    fun aMissingSuccessFieldIsTreatedAsFailureNotAsSuccess() {
        val thrown = runCatching { LibXrayInvoke.parse("ping", """{"data":{"delay":1}}""") }.exceptionOrNull()

        (thrown is XrayException) shouldBe true
    }
}
