// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

private const val MATRIX_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

/**
 * The protocol × container support matrix, as a test rather than a claim.
 *
 * ARCHITECTURE.md §7 lists five link protocols and four container shapes, which
 * reads as twenty working combinations. It is sixteen. The remaining gaps are
 * not bugs — nobody has written those branches — but §7 stated the capability
 * unqualified, so the table there and this test exist to keep the real shape
 * visible and to fail if a cell silently changes.
 *
 * Clash/`vless` used to be the cell that mattered most: VLESS is the only
 * protocol `:core:xray` can actually emit a config for, and it was the one
 * cell Clash lacked, so a Clash import produced profiles that all failed at
 * connect. M3 fills that cell, and fills Clash/`socks5` alongside it, closing
 * out the Clash column entirely — see the matrix in §7.
 *
 * Each cell asserts only whether a profile comes out, because that is exactly
 * what the §7 table claims. Field-level correctness for the cells that do work
 * is the job of the per-format tests.
 */
class CapabilityMatrixTest {
    private fun yieldsProfile(input: String): Boolean = SubscriptionParser.parse(input).profiles.isNotEmpty()

    private fun base64(plain: String): String = Base64.getEncoder().encodeToString(plain.toByteArray())

    private val links =
        mapOf(
            "vless" to "vless://$MATRIX_UUID@host.example:443",
            "vmess" to "vmess://" + base64("""{"add":"host.example","port":443,"id":"$MATRIX_UUID"}"""),
            "trojan" to "trojan://s3cret@host.example:443",
            "ss" to "ss://" + base64("aes-256-gcm:s3cret") + "@host.example:8388",
            "socks" to "socks://host.example:1080",
        )

    private val clash =
        mapOf(
            "vless" to "proxies:\n  - {name: N, type: vless, server: host.example, port: 443, uuid: $MATRIX_UUID}",
            "vmess" to "proxies:\n  - {name: N, type: vmess, server: host.example, port: 443, uuid: $MATRIX_UUID}",
            "trojan" to "proxies:\n  - {name: N, type: trojan, server: host.example, port: 443, password: s3cret}",
            "ss" to
                "proxies:\n  - {name: N, type: ss, server: host.example, port: 8388, " +
                "cipher: aes-256-gcm, password: s3cret}",
            "socks" to "proxies:\n  - {name: N, type: socks5, server: host.example, port: 1080}",
        )

    private val xrayJson =
        mapOf(
            "vless" to
                """{"outbounds":[{"protocol":"vless","settings":{"vnext":[""" +
                """{"address":"host.example","port":443,"users":[{"id":"$MATRIX_UUID"}]}]}}]}""",
            "vmess" to
                """{"outbounds":[{"protocol":"vmess","settings":{"vnext":[""" +
                """{"address":"host.example","port":443,"users":[{"id":"$MATRIX_UUID"}]}]}}]}""",
            "trojan" to
                """{"outbounds":[{"protocol":"trojan","settings":{"servers":[""" +
                """{"address":"host.example","port":443,"password":"s3cret"}]}}]}""",
            "ss" to
                """{"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[""" +
                """{"address":"host.example","port":8388,"method":"aes-256-gcm","password":"s3cret"}]}}]}""",
            "socks" to
                """{"outbounds":[{"protocol":"socks","settings":{"servers":[""" +
                """{"address":"host.example","port":1080}]}}]}""",
        )

    @Test
    fun `every protocol parses as a share link`() {
        links.forEach { (protocol, link) ->
            withClue(protocol) { yieldsProfile(link) shouldBe true }
        }
    }

    @Test
    fun `every protocol parses inside a base64 subscription`() {
        // The base64 column can never differ from the link column: the blob is
        // decoded and re-fed through detection, which lands on the link list.
        links.forEach { (protocol, link) ->
            withClue(protocol) { yieldsProfile(base64(link)) shouldBe true }
        }
    }

    @Test
    fun `clash yaml supports vmess, trojan, ss, vless and socks5`() {
        val supported = setOf("vmess", "trojan", "ss", "vless", "socks")
        clash.forEach { (protocol, yaml) ->
            withClue("clash $protocol") { yieldsProfile(yaml) shouldBe (protocol in supported) }
        }
    }

    @Test
    fun `raw xray json supports vless only`() {
        xrayJson.forEach { (protocol, json) ->
            withClue("xray json $protocol") { yieldsProfile(json) shouldBe (protocol == "vless") }
        }
    }

    /**
     * The cell that used to decide whether a Clash import was usable at all.
     * M3 fills it: Clash `vless` now yields a connectable profile, so the
     * inverse of the assertion this test used to make is the one worth
     * pinning going forward.
     */
    @Test
    fun `clash can now express the one protocol the tunnel can use`() {
        val clashVless = clash.getValue("vless")

        yieldsProfile(clashVless) shouldBe true
        SubscriptionParser.parse(clashVless).failures shouldBe emptyList()
    }
}
