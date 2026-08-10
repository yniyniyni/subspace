// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.network

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class FetchOutcomeTest {
    @Test
    fun `Success toString does not contain the response body`() {
        val outcome = FetchOutcome.Success(
            body = "vless://secret-uuid@evil.example:443?security=reality#node",
            headers = mapOf("profile-title" to "Personal VPN plan"),
        )

        outcome.toString() shouldNotContain "secret-uuid"
        outcome.toString() shouldNotContain "evil.example"
    }

    @Test
    fun `Success toString does not contain header values, only header names`() {
        val outcome = FetchOutcome.Success(
            body = "",
            headers = mapOf("profile-title" to "Personal VPN plan", "x-hwid-active" to "true"),
        )

        outcome.toString() shouldNotContain "Personal VPN plan"
        outcome.toString() shouldContain "profile-title"
        outcome.toString() shouldContain "x-hwid-active"
    }

    @Test
    fun `Success toString still surfaces a shape hint for debugging`() {
        val outcome = FetchOutcome.Success(body = "0123456789", headers = emptyMap())

        outcome.toString() shouldBe "Success(body=<redacted, 10 chars>, headers=[])"
    }
}
