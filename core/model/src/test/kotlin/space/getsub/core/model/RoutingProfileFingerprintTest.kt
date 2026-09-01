// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

class RoutingProfileFingerprintTest {
    private fun profileWith(dns: ProfileDns?) =
        RoutingProfile(
            name = "P",
            buckets = mapOf(RouteOutcome.PROXY to RuleBucket(sites = listOf("example.test"))),
            dns = dns,
        )

    @Test
    fun hostOrderDoesNotChangeTheFingerprint() {
        val a = profileWith(ProfileDns(hosts = linkedMapOf("a.test" to "1.1.1.1", "b.test" to "8.8.8.8")))
        val b = profileWith(ProfileDns(hosts = linkedMapOf("b.test" to "8.8.8.8", "a.test" to "1.1.1.1")))

        a.fingerprint() shouldBe b.fingerprint()
    }

    @Test
    fun aChangedResolverChangesTheFingerprint() {
        val a = profileWith(ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1")))
        val b = profileWith(ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8")))

        a.fingerprint() shouldNotBe b.fingerprint()
    }

    @Test
    fun absentFakeDnsIsNotTheSameAsFalse() {
        val a = profileWith(ProfileDns(fakeDns = null))
        val b = profileWith(ProfileDns(fakeDns = false))

        a.fingerprint() shouldNotBe b.fingerprint()
    }

    @Test
    fun lastUpdatedStaysExcluded() {
        val a = profileWith(ProfileDns(fakeDns = true)).copy(lastUpdated = 1L)
        val b = profileWith(ProfileDns(fakeDns = true)).copy(lastUpdated = 2L)

        a.fingerprint() shouldBe b.fingerprint()
    }
}
