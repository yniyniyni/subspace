// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import space.getsub.service.log.stampedBefore
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Ruling R50: a captured line whose `threadtime` stamp predates the capture's
 * own `-T` epoch is a replay, and is dropped. On the Pixel 8, capture S20
 * replayed 10 of S19's lines logged up to ~0.4 s *before* its epoch.
 *
 * The stamp is local time with no year, so every test pins a zone.
 * Unparseable stamps fail open: the line is kept.
 */
class LogcatStampTest {
    private val utc: ZoneId = ZoneOffset.UTC

    private fun epoch(
        at: LocalDateTime,
        zone: ZoneId = utc,
    ): Long = at.atZone(zone).toInstant().toEpochMilli()

    private fun line(stamp: String) = "$stamp  1234  5678 I TunnelService: teardown[lifecycle] done +88ms"

    /** S20's start (10:57:48.911 local) against S19's replayed lines. */
    private val s20Start = epoch(LocalDateTime.of(2026, 9, 24, 10, 57, 48, 911_000_000))

    @Test
    fun `a line stamped before the start epoch is before it`() {
        assertTrue(stampedBefore(line("09-24 10:57:48.535"), s20Start, utc))
        assertTrue(stampedBefore(line("09-24 10:57:48.910"), s20Start, utc))
    }

    @Test
    fun `a line stamped at or after the start epoch is not`() {
        assertFalse("same millisecond must be kept", stampedBefore(line("09-24 10:57:48.911"), s20Start, utc))
        assertFalse(stampedBefore(line("09-24 10:57:48.912"), s20Start, utc))
        assertFalse(stampedBefore(line("09-24 10:59:00.830"), s20Start, utc))
    }

    @Test
    fun `logcat's buffer banner and other unparseable lines fail open`() {
        listOf(
            "--------- beginning of main",
            "--------- beginning of crash",
            "",
            "   continuation of a multi-line message",
            "log capture stopped at its drain deadline; closing lines may be missing",
            "13-45 99:99:99.999  1234  5678 I Tag: an impossible date",
            "09-24 10:57:48  1234  5678 I Tag: no milliseconds",
        ).forEach { assertFalse("dropped an unparseable line: '$it'", stampedBefore(it, s20Start, utc)) }
    }

    @Test
    fun `the stamp is read in the given local zone`() {
        val stockholm = ZoneId.of("Europe/Stockholm") // UTC+2 in September
        val start = epoch(LocalDateTime.of(2026, 9, 24, 10, 57, 48, 911_000_000), stockholm)
        assertTrue(stampedBefore(line("09-24 10:57:48.535"), start, stockholm))
        assertFalse(stampedBefore(line("09-24 10:57:49.000"), start, stockholm))
        // Read as UTC, the same start is 08:57 — a 10:57 stamp would be two hours "after".
        assertFalse(stampedBefore(line("09-24 10:57:48.535"), start, utc))
    }

    @Test
    fun `a line just before midnight is before a start just after it`() {
        val start = epoch(LocalDateTime.of(2026, 9, 25, 0, 0, 0, 100_000_000))
        assertTrue(stampedBefore(line("09-24 23:59:59.900"), start, utc))
        assertFalse(stampedBefore(line("09-25 00:00:00.200"), start, utc))
    }

    @Test
    fun `a december stamp against a january start is last year's, so before`() {
        val start = epoch(LocalDateTime.of(2027, 1, 1, 0, 0, 0, 300_000_000))
        assertTrue(stampedBefore(line("12-31 23:59:59.800"), start, utc))
    }

    @Test
    fun `a january stamp against a december start is next year's, so not before`() {
        val start = epoch(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 800_000_000))
        assertFalse(stampedBefore(line("01-01 00:00:00.300"), start, utc))
        assertTrue(stampedBefore(line("12-31 23:59:59.700"), start, utc))
    }

    @Test
    fun `a leap-day stamp does not break the parse in a neighbouring year`() {
        // 2028 is a leap year; 2027 and 2029 are not.
        val start = epoch(LocalDateTime.of(2028, 2, 29, 12, 0, 0, 0))
        assertTrue(stampedBefore(line("02-29 11:59:59.999"), start, utc))
        assertFalse(stampedBefore(line("02-29 12:00:00.000"), start, utc))
    }

    /**
     * The autumn DST overlap: 02:30 happens twice. A stamp that *could* be at
     * or after the start under either offset is kept — fail open.
     */
    @Test
    fun `an ambiguous stamp in the DST overlap is kept when either reading is not before`() {
        val stockholm = ZoneId.of("Europe/Stockholm")
        // 2026-10-25: clocks go back 03:00 CEST -> 02:00 CET. Start in the
        // second 02:10 (CET, the later instant).
        val start =
            LocalDateTime.of(2026, 10, 25, 2, 10, 0, 0)
                .atZone(stockholm)
                .withLaterOffsetAtOverlap()
                .toInstant()
                .toEpochMilli()
        // Logged after the start, in the second 02:30.
        assertFalse(stampedBefore(line("10-25 02:30:00.000"), start, stockholm))
        // Unambiguously before: 01:59 CEST.
        assertTrue(stampedBefore(line("10-25 01:59:00.000"), start, stockholm))
    }
}
