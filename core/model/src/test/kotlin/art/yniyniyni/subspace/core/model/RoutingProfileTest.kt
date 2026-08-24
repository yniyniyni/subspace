// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class RoutingProfileTest {
    @Test
    fun parsesEveryObservedRouteOrderPermutation() {
        parseRouteOrder("block-proxy-direct") shouldBe
            listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT)
        parseRouteOrder("proxy-direct-block") shouldBe
            listOf(RouteOutcome.PROXY, RouteOutcome.DIRECT, RouteOutcome.BLOCK)
        parseRouteOrder("block-direct-proxy") shouldBe
            listOf(RouteOutcome.BLOCK, RouteOutcome.DIRECT, RouteOutcome.PROXY)
    }

    @Test
    fun rejectsRouteOrderThatIsNotAPermutation() {
        parseRouteOrder("block-proxy") shouldBe null
        parseRouteOrder("block-block-proxy") shouldBe null
        parseRouteOrder("block-proxy-direct-direct") shouldBe null
        parseRouteOrder("block-proxy-bypass") shouldBe null
        parseRouteOrder("") shouldBe null
        parseRouteOrder("block--proxy-direct") shouldBe null
        parseRouteOrder("-block-proxy-direct") shouldBe null
        parseRouteOrder("block-proxy-direct-") shouldBe null
    }

    @Test
    fun routeOrderIsCaseInsensitive() {
        parseRouteOrder("BLOCK-Proxy-direct") shouldBe
            listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT)
    }

    @Test
    fun fingerprintIgnoresFieldsThatDoNotChangeBehaviour() {
        val a = sampleProfile()
        // LastUpdated is the monotonicity gate, not content: a provider that
        // only bumps the timestamp has not changed what the profile does.
        val b = a.copy(lastUpdated = checkNotNull(a.lastUpdated) + 1000)
        a.fingerprint() shouldBe b.fingerprint()
    }

    @Test
    fun fingerprintChangesWhenAnyRuleChanges() {
        val a = sampleProfile()
        val b =
            a.copy(
                buckets = a.buckets + (RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:cn", "example.org"))),
            )
        a.fingerprint() shouldNotBe b.fingerprint()
    }

    @Test
    fun fingerprintIsStableAcrossBucketMapIterationOrder() {
        val ordered = sampleProfile()
        val reorderedBuckets =
            linkedMapOf(
                RouteOutcome.BLOCK to ordered.bucket(RouteOutcome.BLOCK),
                RouteOutcome.DIRECT to ordered.bucket(RouteOutcome.DIRECT),
                RouteOutcome.PROXY to ordered.bucket(RouteOutcome.PROXY),
            )
        val reordered = ordered.copy(buckets = reorderedBuckets)
        ordered.fingerprint() shouldBe reordered.fingerprint()
    }

    @Test
    fun projectsOntoARuleSetPreservingOrderAndStrategy() {
        val set = sampleProfile().toRuleSet()
        set.name shouldBe "RussiaInside"
        set.order shouldBe listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT)
        set.domainStrategy shouldBe DomainStrategy.IP_IF_NON_MATCH
        set.bucket(RouteOutcome.PROXY).sites shouldBe listOf("geosite:cn")
        set.globalProxy shouldBe false
    }

    @Test
    fun requiredGeoFilesFlowsThroughTheProjection() {
        sampleProfile().toRuleSet().requiredGeoFiles() shouldBe setOf("geosite.dat", "geoip.dat")
    }

    private fun sampleProfile(): RoutingProfile {
        val buckets =
            mapOf(
                RouteOutcome.PROXY to RuleBucket(sites = listOf("geosite:cn"), ips = listOf("geoip:cn")),
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8")),
            )
        return RoutingProfile(
            name = "RussiaInside",
            globalProxy = false,
            routeOrder = listOf(RouteOutcome.BLOCK, RouteOutcome.PROXY, RouteOutcome.DIRECT),
            domainStrategy = DomainStrategy.IP_IF_NON_MATCH,
            buckets = buckets,
            geoIpUrl = "https://example.test/geoip.dat",
            geoSiteUrl = "https://example.test/geosite.dat",
            lastUpdated = 1_700_000_000L,
            dns = ProfileDns(remote = DnsResolver(DnsTransport.DOH, domain = "https://dns.test/dns-query")),
            useChunkFiles = true,
        )
    }
}
