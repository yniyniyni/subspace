// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.routing

import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsState
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.ProfileDns
import io.kotest.matchers.shouldBe
import org.junit.Test

class DnsStateTest {
    @Test
    fun noDnsBlockHasNoState() {
        dnsStateOf(dns = null, sniffingEnabled = true) shouldBe DnsState.None
    }

    @Test
    fun validDnsBlockIsAppliedWhenSniffingIsEnabled() {
        val dns = ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "1.1.1.1"))

        dnsStateOf(dns, sniffingEnabled = true) shouldBe DnsState.Applied
    }

    @Test
    fun anOrdinaryEmptyDnsBlockIsAppliedNotInvalid() {
        dnsStateOf(ProfileDns(), sniffingEnabled = true) shouldBe DnsState.Applied
    }

    @Test
    fun rejectedDnsBlockIsInvalid() {
        dnsStateOf(ProfileDns.INVALID, sniffingEnabled = true) shouldBe DnsState.Invalid
    }

    @Test
    fun fakeDnsNeedsSniffingWhenItIsDisabled() {
        val dns = ProfileDns(fakeDns = true)

        dnsStateOf(dns, sniffingEnabled = false) shouldBe DnsState.NeedsSniffing
    }
}
