// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser.routing

import art.yniyniyni.subspace.core.model.DnsTransport
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

class RoutingProfileDnsTest {
    @Test
    fun parsesADohRemoteAndDouDomesticSplit() {
        val result = parseFixture("dns-doh-split.json").shouldBeInstanceOf<ImportResult.Imported>()
        val dns = requireNotNull(result.profile.dns)

        dns.isInvalid shouldBe false
        dns.remote?.transport shouldBe DnsTransport.DOH
        dns.remote?.domain shouldBe "https://cloudflare-dns.com/dns-query"
        dns.domestic?.transport shouldBe DnsTransport.DOU
        dns.domestic?.ip shouldBe "8.8.8.8"
        dns.fakeDns shouldBe false
    }

    @Test
    fun synthesisesTheDohBootstrapHostEntry() {
        val result = parseFixture("dns-doh-split.json").shouldBeInstanceOf<ImportResult.Imported>()
        val dns = requireNotNull(result.profile.dns)

        dns.hosts["cloudflare-dns.com"] shouldBe "1.1.1.1"
    }

    @Test
    fun neverOverwritesAHostEntryTheAuthorWrote() {
        val json =
            """
            {"Name":"P","ProxySites":["example.test"],
             "RemoteDNSType":"DoH","RemoteDNSDomain":"https://cloudflare-dns.com/dns-query",
             "RemoteDNSIP":"1.1.1.1",
             "DnsHosts":{"cloudflare-dns.com":"9.9.9.9"}}
            """.trimIndent()
        val result = parseInline(json).shouldBeInstanceOf<ImportResult.Imported>()

        requireNotNull(result.profile.dns).hosts["cloudflare-dns.com"] shouldBe "9.9.9.9"
    }

    @Test
    fun rejectsAnUnknownTransportWithoutFailingTheImport() {
        val json = """{"Name":"P","ProxySites":["example.test"],"RemoteDNSType":"DoQ","RemoteDNSIP":"1.1.1.1"}"""
        val result = parseInline(json).shouldBeInstanceOf<ImportResult.Imported>()

        requireNotNull(result.profile.dns).isInvalid shouldBe true
        result.profile.entryCount shouldBe 1
    }

    @Test
    fun rejectsADohResolverWhoseDomainIsNotHttps() {
        val json =
            """
            {"Name":"P","ProxySites":["example.test"],
             "RemoteDNSType":"DoH","RemoteDNSDomain":"cloudflare-dns.com"}
            """.trimIndent()
        val result = parseInline(json).shouldBeInstanceOf<ImportResult.Imported>()

        requireNotNull(result.profile.dns).isInvalid shouldBe true
    }

    @Test
    fun rejectsADouResolverWhoseIpIsNotAnAddress() {
        val json =
            """{"Name":"P","ProxySites":["example.test"],"DomesticDNSType":"DoU","DomesticDNSIP":"not-an-ip"}"""
        val result = parseInline(json).shouldBeInstanceOf<ImportResult.Imported>()

        requireNotNull(result.profile.dns).isInvalid shouldBe true
    }

    @Test
    fun readsFakeDnsAsAStringBoolean() {
        val json = """{"Name":"P","ProxySites":["example.test"],"FakeDNS":"true"}"""

        requireNotNull(parseInline(json).shouldBeInstanceOf<ImportResult.Imported>().profile.dns).fakeDns shouldBe true
    }

    @Test
    fun aProfileWithNoDnsKeysHasNoBlock() {
        val json = """{"Name":"P","ProxySites":["example.test"]}"""

        parseInline(json).shouldBeInstanceOf<ImportResult.Imported>().profile.dns shouldBe null
    }
}

private fun parseFixture(name: String): ImportResult {
    val body = requireNotNull(object {}.javaClass.getResource("/routing/$name")).readText()
    return parseInline(body)
}

private fun parseInline(json: String): ImportResult {
    val encoded =
        java.util.Base64
            .getEncoder()
            .encodeToString(json.toByteArray())
    return RoutingProfileImport.parse("happ://routing/add/$encoded")
}
