// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * [retireRetryIfEstablished] is the piece of the P1 retry-cleanup fix that both
 * `attachTun` and `attachRetainedTun` call, and the only piece of it a JVM test can drive
 * directly: `TunnelService` cannot be instantiated here (`VpnService`, and this project
 * carries no Robolectric or mocking library per §10.7), so the *ordering* half of that fix —
 * that both call sites invoke this from inside `lifecycle`, under lock, before `persist` ever
 * suspends — is verified by code reading only, not by any test. This file is the regression
 * seam for the other half: cancellation must run when the transition establishes, and must
 * not run when it does not.
 */
class RetireRetryIfEstablishedTest {
    @Test
    fun `an established transition retires the retry`() {
        var retired = false

        val result = retireRetryIfEstablished(established = true, retireRetry = { retired = true })

        result shouldBe true
        retired shouldBe true
    }

    @Test
    fun `a rejected transition leaves the retry alone`() {
        var retired = false

        val result = retireRetryIfEstablished(established = false, retireRetry = { retired = true })

        result shouldBe false
        retired shouldBe false
    }
}
