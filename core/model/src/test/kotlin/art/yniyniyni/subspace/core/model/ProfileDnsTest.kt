// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class ProfileDnsTest {
    @Test
    fun recognisesIpv4AndIpv6Literals() {
        DnsValidation.isAddressLiteral("1.1.1.1") shouldBe true
        DnsValidation.isAddressLiteral("2606:4700:4700::1111") shouldBe true
        DnsValidation.isAddressLiteral("cloudflare-dns.com") shouldBe false
        DnsValidation.isAddressLiteral("") shouldBe false
    }

    @Test
    fun acceptsOnlyStrictIpv6LiteralsWithoutNetworkResolution() {
        listOf(
            "2001:0db8:0000:0000:0000:ff00:0042:8329",
            "2606:4700:4700::1111",
            "::1",
        ).forEach { literal ->
            DnsValidation.isAddressLiteral(literal) shouldBe true
        }

        listOf(
            "::::",
            "1:2:3:4:5:6:7:8:9",
            "1::2::3",
            "1:2:3:4:5:6:7:gggg",
            "resolver.example.test",
        ).forEach { invalid ->
            DnsValidation.isAddressLiteral(invalid) shouldBe false
        }
    }

    @Test
    fun recognisesHttpsUrlsOnly() {
        DnsValidation.isHttpsUrl("https://cloudflare-dns.com/dns-query") shouldBe true
        DnsValidation.isHttpsUrl("http://cloudflare-dns.com/dns-query") shouldBe false
        DnsValidation.isHttpsUrl("cloudflare-dns.com") shouldBe false
    }

    @Test
    fun extractsTheHostFromADohUrl() {
        DnsValidation.hostOf("https://cloudflare-dns.com/dns-query") shouldBe "cloudflare-dns.com"
        DnsValidation.hostOf("not a url") shouldBe null
    }

    @Test
    fun aBlockWithNoResolversStillCountsWhenItCarriesHostsOrFakeDns() {
        ProfileDns(hosts = mapOf("a.test" to "1.1.1.1")).hasResolver shouldBe false
        ProfileDns(remote = DnsResolver(DnsTransport.DOU, null, "1.1.1.1")).hasResolver shouldBe true
    }

    @Test
    fun toStringRedactsResolversAndHosts() {
        val dns =
            ProfileDns(
                remote = DnsResolver(DnsTransport.DOH, "https://secret-resolver.test/dns-query", "9.9.9.9"),
                hosts = mapOf("secret-host.test" to "9.9.9.9"),
            )

        val rendered = dns.toString()

        rendered shouldNotContain "secret-resolver.test"
        rendered shouldNotContain "secret-host.test"
        rendered shouldNotContain "9.9.9.9"
    }
}
