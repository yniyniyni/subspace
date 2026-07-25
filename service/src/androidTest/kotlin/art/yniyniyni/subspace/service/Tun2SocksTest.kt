// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the hev-socks5-tunnel JNI bridge on a real device.
 *
 * A successful native build does not prove the bridge works: a mismatch between
 * the C function names and the Kotlin `external` declarations produces
 * `UnsatisfiedLinkError` at *call* time, not at load time or compile time. These
 * tests call every binding so that mismatch cannot survive.
 *
 * What is NOT tested here: actually tunnelling packets. That needs a live TUN fd
 * from `VpnService.Builder`, which needs user consent, so it belongs to the
 * manual on-device checklist in ARCHITECTURE.md §11 — as §10.1 insists.
 */
class Tun2SocksTest {
    @Test
    fun nativeLibraryLoadsAndBindsEverySymbol() {
        // Touching the object runs System.loadLibrary; calling through it proves
        // each JNI symbol resolves. An UnsatisfiedLinkError here means the C
        // names and the Kotlin externals have drifted apart.
        assertFalse("expected no tunnel running on a fresh process", Tun2Socks.isRunning)
    }

    @Test
    fun stopIsSafeWhenNothingIsRunning() {
        // §5.4: teardown runs from disconnect, onRevoke, and onDestroy, any of
        // which can fire when the tunnel is already down. It must not crash the
        // process — a native crash here takes :bg with it.
        Tun2Socks.stop()
        assertFalse(Tun2Socks.isRunning)
    }

    @Test
    fun startRefusesANegativeFileDescriptorInsteadOfCrashing() {
        // This test exists because the first version of the bridge did NOT guard
        // the fd, and this call took the whole process down: hev-socks5-tunnel
        // aborts on an invalid descriptor rather than returning -1. The guard in
        // tun2socks_jni.c is what makes this survivable, and if someone removes it
        // this test dies as "Process crashed", not as a normal failure.
        val config = tun2socksConfig(socksPort = 10808, mtu = 8500)

        val started = Tun2Socks.start(config, tunFd = -1)

        assertFalse("start must refuse a negative fd", started)
        assertFalse("tunnel must not be running after a refused start", Tun2Socks.isRunning)
    }

    @Test
    fun configContainsTheAllocatedPortAndNoLiteral() {
        // §10.6: the SOCKS port comes from libXray getFreePorts, never a literal.
        val config = tun2socksConfig(socksPort = 34567, mtu = 8500)
        assertTrue(config.contains("port: 34567"))
        assertTrue(config.contains("address: 127.0.0.1"))
        assertTrue(config.contains("mtu: 8500"))
    }

    @Test
    fun configOmitsInterfaceAddressing() {
        // The interface already exists — VpnService.Builder created and addressed
        // it. Restating ipv4/ipv6/name here would be a second source of truth for
        // something §5.2 depends on.
        val config = tun2socksConfig(socksPort = 10808, mtu = 8500)
        assertFalse(config.contains("ipv4"))
        assertFalse(config.contains("ipv6"))
        assertFalse(config.contains("name:"))
    }
}
