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

    // ── Lever 1 across a restart that keeps the TUN (spec §5.2) ─────────────
    //
    // A restart that keeps the interface never re-runs Builder.establish(), so
    // the TUN goes on advertising what it was built with while the core is
    // rebuilt from current settings. These pin the *decision*: two total
    // functions of their arguments, answering "what would this plan advertise"
    // and "do the recorded and the next answer still agree".
    //
    // What they deliberately do NOT cover, stated so nobody reads them as wider
    // than they are: the wiring that acts on that decision. The branch lives in
    // TunnelService.restartCoreRetainingTun and the recording in attachTun, and
    // neither is reachable from a JVM test — a VpnService.Builder cannot be
    // constructed off-device. Delete the branch and always take the retained
    // path, or never record the address, and every test in this file still
    // passes. Coverage for the effect is device row W8 in
    // docs/agent/research/2026-09-07-m8-device-verification.md, which has not
    // been run; that row is the debt, and these tests are not a substitute for
    // it (§10.1).

    private fun planAdvertising(address: String) =
        DnsPlan(
            servers = listOf(DnsServerSpec(address)),
            hosts = emptyMap(),
            fakeDns = false,
            directMatch = null,
            proxyMatch = null,
        )

    @Test
    fun `a plan with no literal to advertise resolves to the fallback`() {
        advertisedTunDnsAddress(null, "1.1.1.1") shouldBe "1.1.1.1"
    }

    @Test
    fun `a plan with a literal resolves to it`() {
        advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1") shouldBe "77.88.8.8"
    }

    @Test
    fun `an unchanged address keeps the interface`() {
        val pinned = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")
        val next = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")

        retainedTunKeepsAdvertisedDns(pinned, next) shouldBe true
    }

    /**
     * The leak this branch exists for: DNS is turned off mid-session, so the new
     * config emits no port-53 hijack, while the interface still advertises the
     * domestic resolver — which a `geoip:<country>` DIRECT rule sends out in the
     * clear. Keeping the interface here would leak every lookup.
     */
    @Test
    fun `turning DNS off mid-session rebuilds the interface`() {
        val pinned = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")
        val next = advertisedTunDnsAddress(null, "1.1.1.1")

        retainedTunKeepsAdvertisedDns(pinned, next) shouldBe false
    }

    @Test
    fun `turning DNS on mid-session rebuilds the interface`() {
        val pinned = advertisedTunDnsAddress(null, "1.1.1.1")
        val next = advertisedTunDnsAddress(planAdvertising("77.88.8.8").tunAdvertisedAddress(), "1.1.1.1")

        retainedTunKeepsAdvertisedDns(pinned, next) shouldBe false
    }

    /** Nothing recorded means unknown, not unchanged: rebuild rather than guess. */
    @Test
    fun `an unrecorded address rebuilds the interface`() {
        retainedTunKeepsAdvertisedDns(null, "1.1.1.1") shouldBe false
    }
}
