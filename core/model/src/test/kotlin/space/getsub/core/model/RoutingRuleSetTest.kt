// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class RoutingRuleSetTest {
    private fun set(
        order: List<RouteOutcome> = RoutingRuleSet.DEFAULT_ORDER,
        buckets: Map<RouteOutcome, RuleBucket> = emptyMap(),
    ) = RoutingRuleSet(name = "test", buckets = buckets, order = order)

    @Test
    fun `default order is block then proxy then direct`() {
        RoutingRuleSet.DEFAULT_ORDER shouldBe
            listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT)
    }

    @Test
    fun `accepts any permutation of every outcome`() {
        val reversed = listOf(RouteOutcome.DIRECT, RouteOutcome.PROXY, RouteOutcome.BLOCK)
        set(order = reversed).order shouldBe reversed
    }

    @Test
    fun `rejects an order missing an outcome`() {
        shouldThrow<IllegalArgumentException> {
            set(order = listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY))
        }
    }

    @Test
    fun `rejects an order with a duplicate`() {
        shouldThrow<IllegalArgumentException> {
            set(order = listOf(RouteOutcome.BLOCK, RouteOutcome.BLOCK, RouteOutcome.PROXY))
        }
    }

    @Test
    fun `rejects an empty order`() {
        shouldThrow<IllegalArgumentException> { set(order = emptyList()) }
    }

    @Test
    fun `bucket returns an empty bucket for an outcome with no entries`() {
        set().bucket(RouteOutcome.DIRECT) shouldBe RuleBucket()
    }

    @Test
    fun `bucket returns the stored entries`() {
        val bucket = RuleBucket(sites = listOf("geosite:cn"), ips = listOf("10.0.0.0/8"))
        set(buckets = mapOf(RouteOutcome.DIRECT to bucket)).bucket(RouteOutcome.DIRECT) shouldBe bucket
    }

    @Test
    fun `an empty bucket knows it is empty`() {
        RuleBucket().isEmpty shouldBe true
        RuleBucket(sites = listOf("example.com")).isEmpty shouldBe false
        RuleBucket(ips = listOf("10.0.0.0/8")).isEmpty shouldBe false
    }

    // §5.6: entries are the domains and addresses the user visits. The generated
    // data-class toString() would print them verbatim into any log line that
    // interpolates a rule set — a structural guard, matching SubscriptionEntity
    // and FetchOutcome.Success.
    @Test
    fun `toString redacts entries but keeps shape`() {
        val directBucket = RuleBucket(sites = listOf("secret.example"))
        val blockBucket = RuleBucket(ips = listOf("203.0.113.7/32"))
        val buckets = mapOf(RouteOutcome.DIRECT to directBucket, RouteOutcome.BLOCK to blockBucket)
        val subject = set(buckets = buckets)

        val rendered = subject.toString()

        rendered shouldNotContain "secret.example"
        rendered shouldNotContain "203.0.113.7"
        rendered shouldContain "2 entries"
        rendered shouldContain "IP_IF_NON_MATCH"
    }
}
