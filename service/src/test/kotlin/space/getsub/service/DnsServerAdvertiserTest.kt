// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import io.kotest.matchers.shouldBe
import org.junit.Test
import space.getsub.core.xray.DnsPlan
import space.getsub.core.xray.DnsServerSpec

class DnsServerAdvertiserTest {
    @Test
    fun `a rejected planned address is retried with the fallback`() {
        val added = mutableListOf<String>()
        val plannedAddress =
            DnsPlan(
                servers = listOf(DnsServerSpec("2001:db8::1")),
                hosts = emptyMap(),
                fakeDns = false,
                directMatch = null,
                proxyMatch = null,
            ).tunAdvertisedAddress()

        val usedFallback =
            addDnsServerOrFallback(
                plannedAddress = plannedAddress,
                fallbackAddress = "1.1.1.1",
            ) { address ->
                added += address
                require(address != "2001:db8::1") { "rejected" }
            }

        usedFallback shouldBe true
        added shouldBe listOf("2001:db8::1", "1.1.1.1")
    }
}
