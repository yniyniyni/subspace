// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.DomainStrategy
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingProfile
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Spec §9's mandated fingerprint-stability case.
 *
 * §4.3: M6 stored the DNS block as an opaque string and folded it into the
 * fingerprint verbatim, so canonicalising on write would have moved the
 * fingerprint of every DNS-carrying profile already on a device — and §7.3 makes
 * that fingerprint the silent-no-op gate, so each move costs one unexplained
 * review sheet. The fix is to fold the **typed projection**: a row stored by M6
 * in Happ's key order and a freshly parsed profile then agree regardless of byte
 * order.
 *
 * The other half of that guarantee lives in `RoutingRepository.decideFor`, which
 * recomputes the stored side rather than trusting the `fingerprint` column —
 * pinned by `RoutingRepositoryTest`'s
 * `aRowWhoseStoredFingerprintPredatesTheCurrentAlgorithmIsStillUnchanged`.
 */
class ProfileDnsFingerprintStabilityTest {
    private fun profileWith(dns: ProfileDns?) =
        RoutingProfile(
            name = "p",
            buckets = mapOf(RouteOutcome.DIRECT to art.yniyniyni.subspace.core.model.RuleBucket(sites = listOf("a"))),
            routeOrder = RouteOutcome.entries.toList(),
            domainStrategy = DomainStrategy.IP_IF_NON_MATCH,
            dns = dns,
        )

    @Test
    fun anM6EraRawBlockFingerprintsTheSameAsTheSameBlockInADifferentKeyOrder() {
        val asHappWroteIt =
            """
            {"RemoteDNSType":"DoH","RemoteDNSDomain":"https://cloudflare-dns.com/dns-query",
             "RemoteDNSIP":"1.1.1.1","DnsHosts":{"b.test":"2.2.2.2","a.test":"1.1.1.1"},"FakeDNS":"true"}
            """.trimIndent()
        val sameContentDifferentBytes =
            """
            { "FakeDNS": "true", "DnsHosts": { "a.test": "1.1.1.1", "b.test": "2.2.2.2" },
              "RemoteDNSIP": "1.1.1.1", "RemoteDNSDomain": "https://cloudflare-dns.com/dns-query",
              "RemoteDNSType": "DoH" }
            """.trimIndent()

        profileWith(ProfileDnsCodec.decode(asHappWroteIt)).fingerprint() shouldBe
            profileWith(ProfileDnsCodec.decode(sameContentDifferentBytes)).fingerprint()
    }

    @Test
    fun aRawBlockAndItsCanonicalReEncodingFingerprintIdentically() {
        val raw =
            """{"DomesticDNSType":"DoU","DomesticDNSIP":"8.8.8.8","FakeDNS":"false"}"""
        val decoded = ProfileDnsCodec.decode(raw)

        profileWith(decoded).fingerprint() shouldBe
            profileWith(ProfileDnsCodec.decode(ProfileDnsCodec.encode(decoded))).fingerprint()
    }

    @Test
    fun aProfileCarryingNoDnsBlockIsUnaffectedByTheDnsFields() {
        profileWith(null).fingerprint() shouldBe profileWith(null).fingerprint()
    }
}
