// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.model

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * §5.6 as a structural guard on the classes that hold the actual credentials.
 *
 * These five carry the server address, the UUID, the password and the REALITY key material.
 * Kotlin's generated `toString()` prints all of it, so one `Log.d("$outbound")`, or any exception
 * message that interpolates a profile, would leak the whole set. `:core:data` guarded its own
 * entities early; these were missed, which is the more dangerous half — a branch review found the
 * gap.
 *
 * Every fixture value below is deliberately unique and greppable, so a test that passes only
 * because a field happened to be empty is not possible.
 */
class OutboundRedactionTest {
    private val stream = StreamSettings(network = "tcp", security = Security.None)

    @Test
    fun `a vless outbound prints neither its address nor its uuid`() {
        val rendered =
            VlessOutbound(
                address = "secret-host-alpha.example.net",
                port = 443,
                uuid = "11111111-2222-3333-4444-555555555555",
                flow = "xtls-rprx-vision",
                stream = stream,
            ).toString()

        rendered shouldNotContain "secret-host-alpha.example.net"
        rendered shouldNotContain "11111111-2222-3333-4444-555555555555"
        // Still useful for debugging: shape survives, content does not.
        rendered shouldContain "443"
        rendered shouldContain "xtls-rprx-vision"
    }

    @Test
    fun `a vmess outbound prints neither its address nor its uuid`() {
        val rendered =
            VmessOutbound(
                address = "secret-host-bravo.example.net",
                port = 8443,
                uuid = "66666666-7777-8888-9999-000000000000",
                alterId = 0,
                security = "auto",
                stream = stream,
            ).toString()

        rendered shouldNotContain "secret-host-bravo.example.net"
        rendered shouldNotContain "66666666-7777-8888-9999-000000000000"
    }

    @Test
    fun `a trojan outbound prints neither its address nor its password`() {
        val rendered =
            TrojanOutbound(
                address = "secret-host-charlie.example.net",
                port = 443,
                password = "SECRETTROJANPASSWORD",
                stream = stream,
            ).toString()

        rendered shouldNotContain "secret-host-charlie.example.net"
        rendered shouldNotContain "SECRETTROJANPASSWORD"
    }

    @Test
    fun `a shadowsocks outbound prints neither its address nor its password`() {
        val rendered =
            ShadowsocksOutbound(
                address = "secret-host-delta.example.net",
                port = 8388,
                method = "aes-256-gcm",
                password = "SECRETSSPASSWORD",
            ).toString()

        rendered shouldNotContain "secret-host-delta.example.net"
        rendered shouldNotContain "SECRETSSPASSWORD"
        // The cipher is not a secret, and knowing it is what makes a bug report actionable.
        rendered shouldContain "aes-256-gcm"
    }

    @Test
    fun `a socks outbound prints neither its address nor its credentials`() {
        val rendered =
            SocksOutbound(
                address = "secret-host-echo.example.net",
                port = 1080,
                username = "SECRETUSERNAME",
                password = "SECRETSOCKSPASSWORD",
            ).toString()

        rendered shouldNotContain "secret-host-echo.example.net"
        rendered shouldNotContain "SECRETUSERNAME"
        rendered shouldNotContain "SECRETSOCKSPASSWORD"
    }

    @Test
    fun `interpolating an outbound into a message cannot leak it`() {
        // The realistic shape of the leak: not a deliberate log of the field, but a string
        // template in an error path that happens to include the whole object.
        val outbound =
            VlessOutbound(
                address = "secret-host-foxtrot.example.net",
                port = 443,
                uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                flow = null,
                stream = stream,
            )

        val message = "could not connect to $outbound"

        message shouldNotContain "secret-host-foxtrot.example.net"
        message shouldNotContain "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    }
}
