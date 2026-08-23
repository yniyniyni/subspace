// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data.serialization

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.ProfileDns
import io.kotest.matchers.shouldBe
import org.junit.Test

class ProfileDnsCodecTest {
    @Test
    fun roundTripsAFullBlock() {
        val dns =
            ProfileDns(
                remote = DnsResolver(DnsTransport.DOH, "https://cloudflare-dns.com/dns-query", "1.1.1.1"),
                domestic = DnsResolver(DnsTransport.DOU, null, "8.8.8.8"),
                hosts = mapOf("cloudflare-dns.com" to "1.1.1.1"),
                fakeDns = true,
            )

        ProfileDnsCodec.decode(ProfileDnsCodec.encode(dns)) shouldBe dns
    }

    @Test
    fun encodesHostsInSortedOrderRegardlessOfInsertionOrder() {
        val a = ProfileDns(hosts = linkedMapOf("b.test" to "8.8.8.8", "a.test" to "1.1.1.1"))
        val b = ProfileDns(hosts = linkedMapOf("a.test" to "1.1.1.1", "b.test" to "8.8.8.8"))

        ProfileDnsCodec.encode(a) shouldBe ProfileDnsCodec.encode(b)
    }

    @Test
    fun roundTripsTheInvalidSentinel() {
        val encoded = ProfileDnsCodec.encode(ProfileDns.INVALID)

        requireNotNull(ProfileDnsCodec.decode(encoded)).isInvalid shouldBe true
    }

    @Test
    fun decodesNullAndGarbageToNull() {
        ProfileDnsCodec.decode(null) shouldBe null
        ProfileDnsCodec.decode("") shouldBe null
        ProfileDnsCodec.decode("{not json") shouldBe null
    }

    @Test
    fun readsAnM6EraRawBlockWrittenInHappKeys() {
        val m6 =
            """{"RemoteDNSType":"DoU","RemoteDNSIP":"1.1.1.1","FakeDNS":"false"}"""

        val decoded = requireNotNull(ProfileDnsCodec.decode(m6))

        decoded.remote?.ip shouldBe "1.1.1.1"
        decoded.fakeDns shouldBe false
    }
}
