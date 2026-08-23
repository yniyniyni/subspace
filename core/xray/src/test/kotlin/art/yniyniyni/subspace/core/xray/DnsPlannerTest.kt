// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import io.kotest.matchers.shouldBe
import org.junit.Test

class DnsPlannerTest {
    private val defaultSetting = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1")

    private fun ruleSet(directSites: List<String>) =
        RoutingRuleSet(
            id = 1,
            name = "R",
            buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = directSites)),
        )

    @Test
    fun `nothing asking for dns yields no plan`() {
        DnsPlanner.plan(null, defaultSetting, null, sniffingEnabled = true) shouldBe null
    }

    @Test
    fun `a non-default setting yields a plan`() {
        val plan = DnsPlanner.plan(null, DnsResolver(DnsTransport.DOU, ip = "9.9.9.9"), null, true)

        requireNotNull(plan).servers.single().address shouldBe "9.9.9.9"
    }

    @Test
    fun `a profile block wins over the setting`() {
        val profile = ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8"))

        val plan = DnsPlanner.plan(profile, DnsResolver(DnsTransport.DOU, ip = "9.9.9.9"), null, true)

        requireNotNull(plan).servers.single().address shouldBe "8.8.8.8"
    }

    @Test
    fun `an invalid profile block falls back to the setting`() {
        val plan = DnsPlanner.plan(ProfileDns.INVALID, DnsResolver(DnsTransport.DOU, ip = "9.9.9.9"), null, true)

        requireNotNull(plan).servers.single().address shouldBe "9.9.9.9"
    }

    @Test
    fun `the domestic server is scoped to the direct bucket and skips fallback`() {
        val profile =
            ProfileDns(
                remote = DnsResolver(DnsTransport.DOU, ip = "1.0.0.1"),
                domestic = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8"),
            )

        val plan = requireNotNull(DnsPlanner.plan(profile, defaultSetting, ruleSet(listOf("geosite:cn")), true))

        val domestic = plan.servers.first { it.address == "8.8.8.8" }
        domestic.domains shouldBe listOf("geosite:cn")
        domestic.skipFallback shouldBe true
        plan.servers.last().address shouldBe "1.0.0.1"
    }

    @Test
    fun `a domestic server with no direct bucket to scope is omitted entirely`() {
        val profile =
            ProfileDns(
                remote = DnsResolver(DnsTransport.DOU, ip = "1.0.0.1"),
                domestic = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8"),
            )

        val plan = requireNotNull(DnsPlanner.plan(profile, defaultSetting, ruleSet(emptyList()), true))

        plan.servers.map { it.address } shouldBe listOf("1.0.0.1")
    }

    @Test
    fun `a domestic-only profile emits it unscoped and does not borrow the setting`() {
        val profile = ProfileDns(domestic = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8"))

        val plan = requireNotNull(DnsPlanner.plan(profile, defaultSetting, ruleSet(listOf("geosite:cn")), true))

        plan.servers.map { it.address } shouldBe listOf("8.8.8.8")
        plan.servers.single().domains shouldBe emptyList()
    }

    @Test
    fun `identical resolvers collapse to a single direct match`() {
        val same = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8")
        val profile = ProfileDns(remote = same, domestic = same)

        val plan = requireNotNull(DnsPlanner.plan(profile, defaultSetting, ruleSet(listOf("geosite:cn")), true))

        plan.directMatch shouldBe "8.8.8.8"
        plan.proxyMatch shouldBe null
    }

    @Test
    fun `hosts and fakedns apply on top of the setting when no resolver is named`() {
        val profile = ProfileDns(hosts = mapOf("a.test" to "1.1.1.1"), fakeDns = true)

        val plan = requireNotNull(DnsPlanner.plan(profile, defaultSetting, null, true))

        plan.hosts shouldBe mapOf("a.test" to "1.1.1.1")
        plan.fakeDns shouldBe true
        plan.servers.single().address shouldBe "1.1.1.1"
    }

    @Test
    fun `fakedns is refused when sniffing is off`() {
        val profile = ProfileDns(fakeDns = true)

        val plan = requireNotNull(DnsPlanner.plan(profile, defaultSetting, null, sniffingEnabled = false))

        plan.fakeDns shouldBe false
    }

    @Test
    fun `a dns block asking for nothing is treated as absent`() {
        // R2: a block that only carries fakeDns = false (or an empty hosts map)
        // has hasResolver == false, hosts.isEmpty(), and fakeDns != true. Nothing
        // in it asks for DNS behaviour, so it must fall through to the same
        // absent-block check as a genuinely missing block, not be treated as
        // "effective" just because it is non-null. ProfileDns.INVALID is also a
        // default-constructed instance, so this guards against comparing with
        // `==` instead of the identity-based `isInvalid`.
        val profile = ProfileDns(fakeDns = false)

        val plan = DnsPlanner.plan(profile, defaultSetting, null, sniffingEnabled = true)

        plan shouldBe null
    }
}
