// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class SubscriptionRequestTest {
    @Test
    fun `toString does not contain the subscription url`() {
        val request = SubscriptionRequest(
            url = "https://panel.example/api/sub/super-secret-token",
            hwidEnabled = true,
            userAgentOverride = null,
            timeoutSeconds = 9,
        )

        request.toString() shouldNotContain "super-secret-token"
        request.toString() shouldNotContain "panel.example"
    }

    @Test
    fun `toString still surfaces the non-secret fields, for debugging`() {
        val request = SubscriptionRequest(
            url = "https://panel.example/api/sub/super-secret-token",
            hwidEnabled = false,
            userAgentOverride = "v2rayNG/1.8.5",
            timeoutSeconds = 12,
        )

        request.toString() shouldBe
            "SubscriptionRequest(url=<redacted>, hwidEnabled=false, " +
            "userAgentOverride=v2rayNG/1.8.5, timeoutSeconds=12)"
    }
}
