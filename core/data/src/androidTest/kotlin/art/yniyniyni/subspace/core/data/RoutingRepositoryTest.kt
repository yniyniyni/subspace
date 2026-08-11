// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.testing.InMemoryRoutingStack
import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class RoutingRepositoryTest {
    private lateinit var stack: InMemoryRoutingStack

    @Before
    fun setUp() {
        stack = InMemoryRoutingStack(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = stack.close()

    private val sample =
        RoutingRuleSet(
            name = "domestic direct",
            buckets = mapOf(
                RouteOutcome.DIRECT to
                    RuleBucket(sites = listOf("geosite:cn"), ips = listOf("geoip:cn", "10.0.0.0/8")),
                RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
            ),
            order = listOf(RouteOutcome.DIRECT, RouteOutcome.BLOCK, RouteOutcome.PROXY),
            domainStrategy = DomainStrategy.IP_ON_DEMAND,
        )

    @Test
    fun roundTripsEveryField() = runTest {
        val id = stack.repository.upsert(sample)

        val loaded = stack.repository.ruleSet(id).shouldNotBeNull()

        loaded.name shouldBe sample.name
        loaded.order shouldBe sample.order
        loaded.domainStrategy shouldBe DomainStrategy.IP_ON_DEMAND
        loaded.bucket(RouteOutcome.DIRECT) shouldBe sample.bucket(RouteOutcome.DIRECT)
        loaded.bucket(RouteOutcome.BLOCK) shouldBe sample.bucket(RouteOutcome.BLOCK)
        loaded.bucket(RouteOutcome.PROXY) shouldBe RuleBucket()
    }

    // An empty TEXT column must read back as an empty list, not as a list
    // containing one empty string — String.split("\n") on "" yields [""].
    @Test
    fun anEmptyBucketReadsBackEmpty() = runTest {
        val id = stack.repository.upsert(RoutingRuleSet(name = "empty"))

        val loaded = stack.repository.ruleSet(id).shouldNotBeNull()

        loaded.entryCount shouldBe 0
        loaded.bucket(RouteOutcome.DIRECT).sites shouldBe emptyList()
        loaded.bucket(RouteOutcome.DIRECT).ips shouldBe emptyList()
    }

    @Test
    fun upsertWithAnExistingIdUpdatesRatherThanInserting() = runTest {
        val id = stack.repository.upsert(sample)

        stack.repository.upsert(sample.copy(id = id, name = "renamed"))

        stack.repository.observeAll().first() shouldHaveSize 1
        stack.repository.ruleSet(id).shouldNotBeNull().name shouldBe "renamed"
    }

    @Test
    fun upsertingANewRuleSetWithAnExistingNameUpdatesTheExistingRow() = runTest {
        val id = stack.repository.upsert(sample)
        val replacement =
            sample.copy(
                id = 0,
                buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("domain:proxy.example"))),
                domainStrategy = DomainStrategy.AS_IS,
            )

        stack.repository.upsert(replacement) shouldBe id

        stack.repository.observeAll().first() shouldHaveSize 1
        val loaded = stack.repository.ruleSet(id).shouldNotBeNull()
        loaded.domainStrategy shouldBe DomainStrategy.AS_IS
        loaded.bucket(RouteOutcome.PROXY).sites shouldBe listOf("domain:proxy.example")
        loaded.bucket(RouteOutcome.DIRECT) shouldBe RuleBucket()
    }

    @Test
    fun rejectsBlankAndNewlineEntriesWithoutPersistingThem() = runTest {
        val blank =
            RoutingRuleSet(
                name = "blank",
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf(""))),
            )
        val newline =
            RoutingRuleSet(
                name = "newline",
                buckets = mapOf(RouteOutcome.BLOCK to RuleBucket(ips = listOf("10.0.0.0/8\n10.0.1.0/24"))),
            )

        runCatching { stack.repository.upsert(blank) }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
        runCatching { stack.repository.upsert(newline) }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()

        stack.repository.observeAll().first() shouldHaveSize 0
    }

    @Test
    fun deleteRemovesTheRow() = runTest {
        val id = stack.repository.upsert(sample)

        stack.repository.delete(id)

        stack.repository.ruleSet(id) shouldBe null
        stack.repository.observeAll().first() shouldHaveSize 0
    }

    @Test
    fun observeAllEmitsInNameOrder() = runTest {
        stack.repository.upsert(RoutingRuleSet(name = "zulu"))
        stack.repository.upsert(RoutingRuleSet(name = "alpha"))

        stack.repository.observeAll().first().map { it.name } shouldBe listOf("alpha", "zulu")
    }
}
