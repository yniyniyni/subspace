// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.TrojanOutbound
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.Base64

/**
 * The same server, expressed three ways, must produce the same outbound.
 *
 * This is what catches a field silently dropped in one format's path only —
 * the kind of bug where a subscription works and the identical Clash config
 * connects to the wrong port, and nothing anywhere reports an error.
 */
class CrossFormatTest {
    private val link = "trojan://s3cret@host.example:443?sni=a.example#Berlin"

    private val clash =
        """
        proxies:
          - name: "Berlin"
            type: trojan
            server: host.example
            port: 443
            password: s3cret
            sni: a.example
        """.trimIndent()

    @Test
    fun `link and base64 list agree`() {
        val fromLink = SubscriptionParser.parse(link).profiles.single()
        val blob = Base64.getEncoder().encodeToString(link.toByteArray())
        val fromBlob = SubscriptionParser.parse(blob).profiles.single()
        fromBlob.outbound shouldBe fromLink.outbound
    }

    /**
     * Full outbound equality, not a field-by-field subset.
     *
     * The subset version asserted address, port and password while both
     * fixtures also set `sni` — so a transport or security field dropped on one
     * path only was exactly what this test was meant to catch and exactly what
     * it could not see. Comparing the whole outbound also means a field added
     * to the model in a later milestone is covered here the day it lands,
     * rather than the day someone remembers to extend the list.
     */
    @Test
    fun `link and clash agree on the outbound`() {
        val fromLink =
            SubscriptionParser
                .parse(link)
                .profiles
                .single()
                .outbound as TrojanOutbound
        val fromClash =
            SubscriptionParser
                .parse(clash)
                .profiles
                .single()
                .outbound as TrojanOutbound

        fromClash shouldBe fromLink
    }

    @Test
    fun `the same server gets the same id in every format`() {
        val fromLink = SubscriptionParser.parse(link).profiles.single()
        val fromClash = SubscriptionParser.parse(clash).profiles.single()
        fromClash.id shouldBe fromLink.id
    }
}
