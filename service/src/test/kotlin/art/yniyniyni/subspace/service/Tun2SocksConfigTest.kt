// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * Pure string assertions, so they belong on the JVM.
 *
 * They previously lived in `androidTest`, where any CI without attached hardware
 * skips them silently — a test that cannot run is a test that does not exist.
 */
class Tun2SocksConfigTest {
    @Test
    fun `carries the port it is given`() {
        // §10.6: the SOCKS port is allocated by libXray getFreePorts at connect
        // time. This checks the plumbing, not the caller — nothing here stops
        // someone passing a constant, so it is not by itself §10.6 enforcement.
        tun2socksConfig(socksPort = 34567, mtu = 8500) shouldContain "port: 34567"
    }

    @Test
    fun `points the tunnel at loopback`() {
        // §6's companion: the Xray inbound binds 127.0.0.1 only, so the tunnel
        // must dial the same place. Anything else would not reach the core.
        tun2socksConfig(socksPort = 10808, mtu = 8500) shouldContain "address: 127.0.0.1"
    }

    @Test
    fun `carries the mtu it is given`() {
        tun2socksConfig(socksPort = 10808, mtu = 1500) shouldContain "mtu: 1500"
    }

    @Test
    fun `omits interface addressing`() {
        // The interface already exists — VpnService.Builder created and addressed
        // it, and upstream ignores these keys entirely when handed an external fd.
        // Restating them here would be a second source of truth for something
        // §5.2 depends on.
        val config = tun2socksConfig(socksPort = 10808, mtu = 8500)
        config shouldNotContain "ipv4"
        config shouldNotContain "ipv6"
        config shouldNotContain "name:"
    }

    @Test
    fun `enables udp relay`() {
        // Without this, UDP is dropped and DNS over the tunnel fails — a §5.2
        // leak that looks like "some sites do not load".
        tun2socksConfig(socksPort = 10808, mtu = 8500) shouldContain "udp: 'udp'"
    }
}
