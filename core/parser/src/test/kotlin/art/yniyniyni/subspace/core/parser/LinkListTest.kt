// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import io.kotest.matchers.shouldBe
import org.junit.Test

private const val U = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"

class LinkListTest {
    @Test
    fun `parses several links`() {
        val text = "vless://$U@a.example:443\ntrojan://pw@b.example:443"
        val outcome = parseLinkList(text)

        outcome.profiles.size shouldBe 2
        outcome.failures.size shouldBe 0
    }

    @Test
    fun `one bad line does not lose the good ones`() {
        val good = (1..199).joinToString("\n") { "vless://$U@h$it.example:443" }
        val outcome = parseLinkList("$good\n!!!garbage!!!")

        outcome.profiles.size shouldBe 199
        outcome.failures.size shouldBe 1
    }

    @Test
    fun `failure index points at the filtered entry`() {
        val text = "vless://$U@a.example:443\n!!!garbage!!!\nvless://$U@c.example:443"
        val outcome = parseLinkList(text)

        outcome.failures[0].index shouldBe 1
    }

    @Test
    fun `blank lines are skipped and do not affect failure indexing`() {
        val text = "\n vless://$U@a.example:443 \n\n!!!garbage!!!\n\nvless://$U@c.example:443\n"
        val outcome = parseLinkList(text)

        outcome.profiles.size shouldBe 2
        outcome.failures.size shouldBe 1
        outcome.failures[0].index shouldBe 1
    }

    @Test
    fun `handles CRLF line endings`() {
        val text = "vless://$U@a.example:443\r\ntrojan://pw@b.example:443\r\n"

        parseLinkList(text).profiles.size shouldBe 2
    }

    @Test
    fun `empty or whitespace-only input yields empty`() {
        parseLinkList("") shouldBe ParseOutcome.EMPTY
        parseLinkList(" \n\t\r\n ") shouldBe ParseOutcome.EMPTY
    }

    @Test
    fun `multiple failures and protocols retain their order`() {
        val text =
            "ss://YWVzLTI1Ni1nY206c3ludGhldGljLXBhc3M=@ss.example:8388\n" +
                "invalid\nsocks://socks.example:1080\nftp://host.example:21"
        val outcome = parseLinkList(text)

        outcome.profiles.size shouldBe 2
        outcome.profiles.map { it.outbound.address } shouldBe listOf("ss.example", "socks.example")
        outcome.failures.map { it.index } shouldBe listOf(1, 3)
    }
}
