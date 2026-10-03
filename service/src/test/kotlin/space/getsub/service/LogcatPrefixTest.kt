// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Test
import space.getsub.service.log.LOGCAT_PREFIX_PATTERN
import space.getsub.service.log.logcatPrefixLength

/**
 * Row 7, Pass 4 (`docs/agent/research/2026-09-26-m8.5-row7-release.md`): the
 * prefix split was 7.5 % of the capture thread as a regex. [logcatPrefixLength]
 * must agree with [LOGCAT_PREFIX_PATTERN] on every line, because the prefix is
 * kept verbatim and only the body is redacted (ARCHITECTURE.md §5.6).
 */
class LogcatPrefixTest {
    private fun byRegex(line: String): Int? = LOGCAT_PREFIX_PATTERN.matchEntire(line)?.groupValues?.get(1)?.length

    private val lines =
        listOf(
            "10-03 13:22:25.657 15924 16532 I GoLog   : 2026/10/03 10:22:25.651837 [Warning] [3909243532] x",
            "09-16 08:01:02.003  1234  5678 W TunnelService: stopTunnel: phase=1",
            "09-16 08:01:02.003 1 2 E subspace-tun2socks:",
            "09-16 08:01:02.003 1 2 E tag with spaces :   body",
            "09-16 08:01:02.003 1 2 D t:\tbody",
            "09-16 08:01:02.003 1 2 X Tag: wrong priority",
            "09-16 08:01:02.003 1 2 I :empty tag",
            "09-16 08:01:02.003 1 2 I  NoColonAtAll",
            "9-16 08:01:02.003 1 2 I Tag: one-digit month",
            "09-16 08:01:02.03 1 2 I Tag: two-digit millis",
            "09-16 08:01:02.003 1 2 I Tag: body: with: colons",
            "09-16 08:01:02.003 x 2 I Tag: pid not a number",
            "09-16\t08:01:02.003\t1\t2\tI\tTag:\tbody",
            "--------- beginning of main",
            "",
            "09-16 08:01:02.003 1 2 I Tag: trailing CR\r",
            "09-16 08:01:02.003 1 2 I Tag\nsplit: body",
            "09-16 08:01:02.003 1 2 I Tag: café",
            "\u0660\u0669-16 08:01:02.003 1 2 I Tag: Arabic-Indic month digits",
        )

    @Test
    fun `the hand parser agrees with the regex on every line shape`() {
        lines.forEach { line -> assertEquals(line, byRegex(line), logcatPrefixLength(line)) }
    }
}
