// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.SocksOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Clash `type: socks5` entries.
 *
 * Split out of `ClashYamlTest.kt` rather than appended to it: that file was
 * already at detekt's `LargeClass` threshold, and SOCKS's tests don't share
 * fixtures with the TLS/REALITY/transport-heavy tests living there — the same
 * kind of split `ClashYamlNode.kt` and `ClashTransport.kt` are for the
 * production code.
 */
class ClashYamlSocksTest {
    @Test
    fun `socks5 with credentials parses`() {
        val yaml =
            """
            proxies:
              - name: Local SOCKS
                type: socks5
                server: 198.51.100.11
                port: 1080
                username: alice
                password: hunter2
            """.trimIndent()

        val outbound = parseClashYaml(yaml).profiles.single().outbound as SocksOutbound

        outbound.address shouldBe "198.51.100.11"
        outbound.port shouldBe 1080
        outbound.username shouldBe "alice"
        outbound.password shouldBe "hunter2"
    }

    @Test
    fun `socks5 without credentials parses as anonymous`() {
        val yaml =
            """
            proxies:
              - name: Anonymous
                type: socks5
                server: 198.51.100.12
                port: 1080
            """.trimIndent()

        val outbound = parseClashYaml(yaml).profiles.single().outbound as SocksOutbound

        outbound.username shouldBe null
        outbound.password shouldBe null
    }

    /**
     * The same server in two containers is one server. Both branches must build
     * the profile id from identical material, or the same entry imported from a
     * Clash file and from a link list becomes two profiles — see
     * `credentialMaterial` in `SocksLink.kt`, reused here for exactly this.
     */
    @Test
    fun `a socks5 proxy gets the same id as the equivalent link`() {
        val yaml =
            """
            proxies:
              - name: Local SOCKS
                type: socks5
                server: 198.51.100.11
                port: 1080
                username: alice
                password: hunter2
            """.trimIndent()
        val link = "socks://alice:hunter2@198.51.100.11:1080#Local SOCKS"

        val fromLink = (parseShareLink(link, 0) as LinkResult.Ok).profile
        parseClashYaml(yaml).profiles.single().id shouldBe fromLink.id
    }
}
