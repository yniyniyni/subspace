// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.regex.Pattern

/**
 * The leading `MM-DD HH:MM:SS.mmm` of a `-v threadtime` line, and nothing
 * else. Only the stamp is parsed: the rest of the line is not looked at here,
 * and every line this keeps still goes through `redactLine` unchanged.
 */
private val THREADTIME_STAMP: Pattern =
    Pattern.compile(
        """(?<month>\d{2})-(?<day>\d{2})\s+(?<hour>\d{2}):(?<minute>\d{2}):(?<second>\d{2})\.(?<millis>\d{3})\s""",
    )

private const val NANOS_PER_MILLI = 1_000_000

/**
 * True when [line]'s `threadtime` stamp is strictly earlier than
 * [sinceEpochMillis], this capture's own `-T` epoch — a replayed line from
 * before the session, which [LogCapture] drops (ruling R50).
 *
 * **Why this exists.** `-T <epoch>` is not exact. On the Pixel 8, capture S20
 * replayed 10 of the previous session's lines, logged up to ~0.4 s *before*
 * its epoch (device record 2026-09-24, N1 item 8) — so the ring held 10
 * duplicates and a timestamp regression. The cause inside logd is unknown;
 * this filter does not depend on it.
 *
 * **What it keeps.** Anything stamped at or after the epoch, including the
 * same millisecond — `threadtime` truncates to the millisecond, as does the
 * epoch, so a line in the epoch's own millisecond may be a replay or may be
 * this session's; keeping it is the side that never loses a real line.
 *
 * **Fails open.** A line with no parseable stamp — logcat's
 * `--------- beginning of main` banner, a continuation line, one of
 * [LogCapture]'s markers, an impossible date — returns false and is kept.
 * Capture completeness matters more than a stray replay. (Redaction fails
 * *closed*; this is not redaction and is not on that path.)
 *
 * **Local time, no year.** The stamp is in [zone] with no year. It is read
 * in whichever of the start's year, the one before, or the one after lands
 * nearest the start — so a `12-31` stamp against a `01-01` start is last
 * year's (before), and a `01-01` stamp against a `12-31` start is next
 * year's (not before). A date that does not exist in a candidate year
 * (`02-29`) just skips that year. In the autumn DST overlap, where one
 * local time names two instants, the later one is used: a stamp is dropped
 * only if it is before the start under *every* reading.
 *
 * **Known residual.** If the wall clock steps backwards mid-session, lines
 * logged after the step are stamped before the epoch and are dropped. The
 * `-T` epoch itself has the same exposure (see [logcatProcessBuilder]).
 */
internal fun stampedBefore(
    line: String,
    sinceEpochMillis: Long,
    zone: ZoneId,
): Boolean {
    val start = Instant.ofEpochMilli(sinceEpochMillis)
    val stamp = leadingStamp(line)?.let { nearestInstant(it, start, zone) }
    return stamp != null && stamp.isBefore(start)
}

/**
 * The date and time of [line]'s leading stamp, or null if it has none or it
 * names an impossible date or time. The date is a [MonthDay]: no year yet.
 */
private fun leadingStamp(line: String): Pair<MonthDay, LocalTime>? {
    val m = THREADTIME_STAMP.matcher(line)
    if (!m.lookingAt()) return null
    // Every group is mandatory and all-digit; an impossible value (month 13) throws, and fails open.
    val field = { name: String -> m.group(name).orEmpty().toInt() }
    return runCatching {
        MonthDay.of(field("month"), field("day")) to
            LocalTime.of(field("hour"), field("minute"), field("second"), field("millis") * NANOS_PER_MILLI)
    }.getOrNull()
}

/**
 * [stamp] read in [zone], in whichever of the start's year and its two
 * neighbours lands nearest [start]; the later offset in a DST overlap. A
 * year the date does not exist in (`02-29`) is skipped.
 */
private fun nearestInstant(
    stamp: Pair<MonthDay, LocalTime>,
    start: Instant,
    zone: ZoneId,
): Instant? {
    val (date, time) = stamp
    val startYear = start.atZone(zone).year
    return (startYear - 1..startYear + 1)
        .filter(date::isValidYear)
        .map { year -> ZonedDateTime.ofLocal(date.atYear(year).atTime(time), zone, null) }
        .map { it.withLaterOffsetAtOverlap().toInstant() }
        .minByOrNull { Duration.between(it, start).abs() }
}
