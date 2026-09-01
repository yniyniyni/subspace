// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data.db

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * Pins §5.6: [SubscriptionEntity.url], [SubscriptionDirectiveEntity.value] and
 * [SubscriptionOverrideEntity.value] are all potential secrets — the plan's own
 * words are "must not reach a log, an exception message, or a generated
 * `toString()`" — and must not reach a generated `toString()`.
 *
 * [SubscriptionEntity.url] was the review finding on this task's initial
 * submission: the default data-class `toString()` printed it verbatim until
 * the override was added. The two directive/override tests below cover a
 * finding made while investigating that one: `DirectiveRegistry` accepts
 * `DirectiveKind.Url` for several directive keys (`support-url`,
 * `fallback-url`, `sub-info-button-link`, `server-address-resolve-dns-domain`,
 * among others) and plaintext credentials for `socks-auth-password` /
 * `http-auth-password`, so a validated, in-vocabulary directive or override
 * `value` can legitimately be a URL or a password — the same class of secret
 * as the subscription URL, just arriving through a different column.
 */
class SubscriptionEntityTest {
    private val secretUrl = "https://provider.example/sub/aB3dEfGhIjKlMnOpQrSt"
    private val secretDirectiveValue = "https://provider.example/support/aB3dEfGh"

    private fun subscription() =
        SubscriptionEntity(
            id = 1,
            groupId = 1,
            url = secretUrl,
            userAgentOverride = null,
            hwidEnabled = true,
            lastFetchedAt = 12345L,
            lastAttemptedAt = 23456L,
            lastFetchStatus = "OK",
            lastFetchDetail = null,
            createdAt = 100L,
        )

    @Test
    fun `toString does not contain the subscription URL`() {
        subscription().toString() shouldNotContain secretUrl
    }

    @Test
    fun `toString redacts the url field but keeps the rest readable`() {
        subscription().toString() shouldBe
            "SubscriptionEntity(id=1, groupId=1, url=<redacted>, " +
            "userAgentOverride=null, hwidEnabled=true, " +
            "lastFetchedAt=12345, lastAttemptedAt=23456, lastFetchStatus=OK, " +
            "lastFetchDetail=null, createdAt=100)"
    }

    @Test
    fun `directive toString does not contain a url-shaped value but keeps the key`() {
        val directive =
            SubscriptionDirectiveEntity(
                subscriptionId = 3,
                key = "support-url",
                value = secretDirectiveValue,
                receivedAt = 100L,
            )

        directive.toString() shouldNotContain secretDirectiveValue
        directive.toString() shouldBe
            "SubscriptionDirectiveEntity(subscriptionId=3, key=support-url, " +
            "value=<redacted>, receivedAt=100)"
    }

    @Test
    fun `override toString does not contain a url-shaped value but keeps the key`() {
        val override =
            SubscriptionOverrideEntity(
                subscriptionId = 3,
                key = "support-url",
                value = secretDirectiveValue,
                pinnedAt = 100L,
            )

        override.toString() shouldNotContain secretDirectiveValue
        override.toString() shouldBe
            "SubscriptionOverrideEntity(subscriptionId=3, key=support-url, " +
            "value=<redacted>, pinnedAt=100)"
    }
}
