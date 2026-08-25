// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
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

    // Fix round 1, Finding 1: an unsafe cast here used to throw
    // IllegalStateException on a structurally-valid-but-malformed row, which
    // contradicts decode()'s own "unreadable storage -> null" contract.
    @Test
    fun toleratesFakeDnsStoredAsAnObjectRatherThanThrowing() {
        val decoded = requireNotNull(ProfileDnsCodec.decode("""{"FakeDNS":{"nested":"true"}}"""))

        decoded.fakeDns shouldBe null
    }

    @Test
    fun toleratesFakeDnsStoredAsAnArrayRatherThanThrowing() {
        val decoded = requireNotNull(ProfileDnsCodec.decode("""{"FakeDNS":["true"]}"""))

        decoded.fakeDns shouldBe null
    }

    // Fix round 1, Finding 2 / controller ruling R7: the previous milestone
    // stored this block raw and unvalidated, so a real row can hold a
    // transport we don't recognise or an address that isn't a literal. That
    // must decode to the same INVALID sentinel the import parser already
    // produces for identical bytes, not to a resolver-less (silently
    // half-applied) block.
    /**
     * Branch review finding 2. The codec rejected a resolver only when *both*
     * address fields were absent, so a DoH entry carrying only an IP — a shape
     * `RoutingProfileImport.resolverOf` rejects outright, and which an M6-era row
     * can hold because M6 stored this column unvalidated — decoded to a resolver
     * whose `xrayAddress()` is null. `DnsPlanner` then saw `hasResolver`, refused
     * to consult the app-level setting, produced no servers, and returned a null
     * plan: the M1 config, no hijack, and the user's own resolver silently
     * discarded while two UI surfaces claimed the profile was setting DNS.
     */
    @Test
    fun aStoredDoHResolverWithNoDomainDecodesToInvalid() {
        ProfileDnsCodec.decode("""{"RemoteDNSType":"DoH","RemoteDNSIP":"1.1.1.1"}""") shouldBe ProfileDns.INVALID
    }

    @Test
    fun aStoredDouResolverWithNoIpDecodesToInvalid() {
        ProfileDnsCodec.decode(
            """{"DomesticDNSType":"DoU","DomesticDNSDomain":"https://dns.example/dns-query"}""",
        ) shouldBe ProfileDns.INVALID
    }

    /**
     * Branch review finding 3: the codec filtered malformed `DnsHosts` entries
     * instead of rejecting the block, which is a half-applied block — the thing
     * spec §5 makes all-or-nothing. An array value is the shape that matters:
     * research §1.2 says it is legal upstream, and coercing it to `""` dropped
     * the mapping silently.
     */
    @Test
    fun aStoredHostsEntryWithAnArrayValueDecodesToInvalid() {
        ProfileDnsCodec.decode(
            """{"DomesticDNSType":"DoU","DomesticDNSIP":"8.8.8.8","DnsHosts":{"a.test":["1.1.1.1"]}}""",
        ) shouldBe ProfileDns.INVALID
    }

    @Test
    fun aStoredHostsEntryWithABlankKeyDecodesToInvalid() {
        ProfileDnsCodec.decode(
            """{"DomesticDNSType":"DoU","DomesticDNSIP":"8.8.8.8","DnsHosts":{"  ":"1.1.1.1"}}""",
        ) shouldBe ProfileDns.INVALID
    }

    @Test
    fun aStoredHostsEntryWithAnUnusableValueDecodesToInvalid() {
        ProfileDnsCodec.decode(
            """{"DomesticDNSType":"DoU","DomesticDNSIP":"8.8.8.8","DnsHosts":{"a.test":"not a host"}}""",
        ) shouldBe ProfileDns.INVALID
    }

    @Test
    fun anUnrecognisedStoredTransportDecodesToInvalid() {
        val stored = """{"RemoteDNSType":"DoQ","RemoteDNSIP":"1.1.1.1"}"""

        requireNotNull(ProfileDnsCodec.decode(stored)).isInvalid shouldBe true
    }

    @Test
    fun aStoredNonLiteralIpDecodesToInvalid() {
        val stored = """{"DomesticDNSType":"DoU","DomesticDNSIP":"not-an-ip"}"""

        requireNotNull(ProfileDnsCodec.decode(stored)).isInvalid shouldBe true
    }

    @Test
    fun anAbsentTypeKeyIsStillAValidDomesticOnlyBlock() {
        val stored = """{"DomesticDNSType":"DoU","DomesticDNSIP":"8.8.8.8"}"""

        val decoded = requireNotNull(ProfileDnsCodec.decode(stored))

        decoded.isInvalid shouldBe false
        decoded.remote shouldBe null
        decoded.domestic?.ip shouldBe "8.8.8.8"
    }

    @Test
    fun anAbsentTypeKeyIsStillAValidResolverlessBlock() {
        val decoded = requireNotNull(ProfileDnsCodec.decode("""{"FakeDNS":"true"}"""))

        decoded.isInvalid shouldBe false
        decoded.remote shouldBe null
        decoded.domestic shouldBe null
    }

    @Test
    fun anEmptyDnsHostsBlockIsValidNotInvalid() {
        val decoded = requireNotNull(ProfileDnsCodec.decode("""{"DnsHosts":{}}"""))

        decoded.isInvalid shouldBe false
        decoded.hosts shouldBe emptyMap()
    }

    // Ruling R8: the codec used to validate only a stored IP, not a stored DoH
    // domain, unlike the import parser's resolverOf. A real row could hold
    // RemoteDNSType: "DoH" with a junk domain and decode cleanly — this junk
    // string would then reach xrayAddress() verbatim and land in dns.servers,
    // producing a config the core rejects outright rather than a block honestly
    // reported as unusable.
    @Test
    fun aStoredDoHResolverWithANonHttpsDomainDecodesToInvalid() {
        val stored = """{"RemoteDNSType":"DoH","RemoteDNSDomain":"not-a-url"}"""

        requireNotNull(ProfileDnsCodec.decode(stored)).isInvalid shouldBe true
    }

    @Test
    fun aStoredDoHResolverWithAPlainHostnameDomainDecodesToInvalid() {
        // A bare hostname (no scheme) is exactly the shape a hand-edited or
        // buggy-exporter row would carry — DnsValidation.isHttpsUrl requires an
        // explicit https:// scheme, and so must this codec.
        val stored = """{"RemoteDNSType":"DoH","RemoteDNSDomain":"dns.example.com"}"""

        requireNotNull(ProfileDnsCodec.decode(stored)).isInvalid shouldBe true
    }

    @Test
    fun aStoredDoHResolverWithAValidHttpsDomainDecodesNormally() {
        val stored = """{"RemoteDNSType":"DoH","RemoteDNSDomain":"https://dns.example/dns-query"}"""

        val decoded = requireNotNull(ProfileDnsCodec.decode(stored))

        decoded.isInvalid shouldBe false
        decoded.remote?.domain shouldBe "https://dns.example/dns-query"
    }
}
