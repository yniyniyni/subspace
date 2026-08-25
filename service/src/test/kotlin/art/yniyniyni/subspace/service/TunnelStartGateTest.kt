// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import io.kotest.matchers.shouldBe
import org.junit.Test

class TunnelStartGateTest {
    @Test
    fun `foreground rejection is terminal before slow startup begins`() {
        val transcript = mutableListOf<String>()

        val result =
            runAfterForegroundEstablished(
                establishForeground = {
                    transcript += "foreground:rejected"
                    false
                },
                onRejected = { transcript += "rejected" },
                launchStartup = { transcript += "startup" },
            )

        result shouldBe InitialForegroundOutcome.Rejected
        transcript shouldBe listOf("foreground:rejected", "rejected")
    }

    @Test
    fun `successful foreground establishment launches startup once`() {
        val transcript = mutableListOf<String>()

        val result =
            runAfterForegroundEstablished(
                establishForeground = {
                    transcript += "foreground:established"
                    true
                },
                onRejected = { transcript += "rejected" },
                launchStartup = { transcript += "startup" },
            )

        result shouldBe InitialForegroundOutcome.Started
        transcript shouldBe listOf("foreground:established", "startup")
    }
}
