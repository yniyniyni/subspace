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
 * How far before a capture's `-T` epoch a head line may be stamped and still
 * count as a replay (ruling R53). The device's replay (capture S20) was 10
 * lines within ~0.4 s before the epoch; 2 s covers that with margin, and a
 * stamp further back than this is a clock step, not a replay.
 */
internal const val REPLAY_WINDOW_MILLIS = 2_000L

/**
 * Drops `-T <epoch>` replays from the **head** of one capture, and nothing
 * else (rulings R50, R53). One instance per capture, used on the capture
 * thread only.
 *
 * **Why this exists.** `-T <epoch>` is not exact. On the Pixel 8, capture S20
 * replayed 10 of the previous session's lines, logged up to ~0.4 s *before*
 * its epoch (device record 2026-09-24, N1 item 8), so the ring held 10
 * duplicates and a timestamp regression. The cause inside logd is unknown;
 * this filter does not depend on it. The replay was entirely at the head of
 * the stream, before the capture's own first line.
 *
 * **The rule.** While the head is open, a line is dropped only if its stamp
 * is within [REPLAY_WINDOW_MILLIS] *before* the epoch
 * (`epoch − 2 s ≤ stamp < epoch`). The head closes, permanently, at the first
 * line with a parseable stamp that is kept: one at or after the epoch, or one
 * more than 2 s before it. From then on every line is kept whatever its
 * stamp.
 *
 * **Why the head, and why bounded (review I-A).** R50's first version
 * compared every line of the session with the epoch, with no lower bound.
 * `logcat` prints stamps in its *current* zone and wall clock, while the
 * epoch is frozen at `start()`. So a westward zone change mid-session
 * (NITZ on a train) or NTP stepping the clock back soon after connect made
 * real lines, including teardown and `done`, look "before" the epoch, and
 * they were dropped silently. A replay can only be at the head, and only
 * just before the epoch, and the filter now claims nothing more.
 *
 * **Why a stamp more than 2 s back also closes the head.** It is kept either
 * way. If it left the head open, a clock stepped back by, say, 5 s would lose
 * the lines that climb back through the last 2 s before the epoch. Closing
 * the head on it means only a replay *older* than 2 s followed by a
 * recent one could leak through, and that costs a duplicate, never a loss.
 *
 * **Unparseable lines leave the head open, and are kept.** This covers
 * logcat's `--------- beginning of main` banner (which precedes any replay),
 * continuation lines and [LogCapture]'s markers. Keeping them fails open,
 * because capture completeness matters more than a stray replay; redaction
 * fails *closed*, and this is not redaction. They say nothing about the
 * clock, so they don't end the window.
 *
 * **Kept at the epoch's own millisecond.** `threadtime` and the epoch both
 * truncate to the millisecond, so a line in that millisecond may be a replay
 * or the session's own. Keeping it never loses a real line.
 *
 * **Known residual.** If NTP steps the clock back by less than 2 s at the
 * very first instant of a capture, before any of its lines is stamped at or
 * after the epoch, the lines stamped inside that window can be dropped:
 * a fraction of a second at most.
 */
internal class ReplayHeadFilter(
    sinceEpochMillis: Long,
    private val zone: ZoneId,
) {
    private val start = Instant.ofEpochMilli(sinceEpochMillis)
    private val windowStart = start.minusMillis(REPLAY_WINDOW_MILLIS)
    private var headOpen = true

    /** False only for a replay at the head; see the class KDoc. */
    fun keep(line: String): Boolean {
        // Unparseable (null stamp): kept, and the head stays open.
        val stamp = if (headOpen) stampInstant(line, start, zone) else null
        val replay = stamp != null && !stamp.isBefore(windowStart) && stamp.isBefore(start)
        if (stamp != null && !replay) headOpen = false
        return !replay
    }
}

/**
 * True when [line]'s `threadtime` stamp is strictly earlier than
 * [sinceEpochMillis]. This is the arithmetic only. It is **not** the drop rule:
 * [ReplayHeadFilter] is, and it bounds the comparison to the head and to a
 * 2 s window (R53). A line with no parseable stamp returns false.
 *
 * **Local time, no year.** The stamp is in [zone] with no year. It is read
 * in whichever of the start's year, the one before, or the one after lands
 * nearest the start. So a `12-31` stamp against a `01-01` start is last
 * year's (before), and a `01-01` stamp against a `12-31` start is next
 * year's (not before). A date that does not exist in a candidate year
 * (`02-29`) just skips that year. In the autumn DST overlap, where one local
 * time names two instants, the later one is used, so a stamp counts as
 * before only if it is before under *every* reading.
 */
internal fun stampedBefore(
    line: String,
    sinceEpochMillis: Long,
    zone: ZoneId,
): Boolean {
    val start = Instant.ofEpochMilli(sinceEpochMillis)
    val stamp = stampInstant(line, start, zone)
    return stamp != null && stamp.isBefore(start)
}

/** [line]'s stamp as an instant, read as [stampedBefore] describes; null if it has none. */
private fun stampInstant(
    line: String,
    start: Instant,
    zone: ZoneId,
): Instant? = leadingStamp(line)?.let { nearestInstant(it, start, zone) }

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
