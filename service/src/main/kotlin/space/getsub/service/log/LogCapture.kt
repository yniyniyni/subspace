// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import space.getsub.core.model.redact
import kotlin.concurrent.thread

/**
 * A single fixed line appended when [LogcatReader.endedWithError] is true after
 * the capture sequence ends — the log otherwise just goes quiet mid-session,
 * indistinguishable from a deliberate stop.
 *
 * Deliberately a literal with no interpolated detail: an exception message or
 * stack trace can quote the very material ARCHITECTURE.md §5.6 forbids writing
 * to disk. It is routed through [redactLine] like every other line so there is
 * one write path to the ring, not a second one that could drift. It carries no
 * logcat prefix, so [redactLine] takes its fail-closed branch and hands it to
 * [redact] whole — which leaves it untouched, but the call site is what
 * matters, not the outcome.
 */
private const val ABNORMAL_END_MARKER = "log capture ended abnormally (stream read failed)"

/**
 * The `-v threadtime` prefix `spawnLogcat` requests:
 * `MM-DD HH:MM:SS.mmm  PID  TID L TAG: `. Anchored to the start of the line —
 * [redactLine] uses [Regex.matchEntire] — so a message body that merely
 * *contains* something shaped like a prefix cannot be mistaken for one.
 *
 * The tag group stops at the first `:`: threadtime never puts a colon inside
 * the tag, that colon is the field separator, so a body that itself starts
 * with a label like `stopTunnel:` is not swallowed into the prefix.
 */
private val LOGCAT_PREFIX_PATTERN =
    Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+[^:\s][^:]*:\s*)(.*)$""")

/**
 * Redacts a captured line's message body only, leaving its logcat prefix —
 * timestamp, pid, tid, priority, tag — verbatim.
 *
 * **Why the split lives here and not in [redact].** [redact]'s positional
 * rules treat any `word:` at the start of a string as a candidate bare host
 * (`BARE_HOST_PREFIX_PATTERN` in `Redaction.kt`), which is exactly the shape
 * of a `-v threadtime` tag. Run over a whole raw line, that rule takes the tag
 * along with everything else: `TunnelService: stopTunnel: phase=...` loses
 * the name of the component that logged it, which guts a log viewer whose
 * whole point is telling you which part of the system was doing what when it
 * wedged. The fix does not belong in [redact] itself: that function is
 * shared, crosses the IPC boundary for `FailureReason`/`ConnectionState`, and
 * has its own pinned test suite serving callers with no tag to preserve and
 * no reason to have that behaviour change under them. Splitting the line here
 * — where the logcat shape is known — leaves [redact] untouched and still
 * does the actual redaction through the one shared function.
 *
 * **Fails closed.** A line that does not match [LOGCAT_PREFIX_PATTERN] gets
 * the whole-line treatment: [redact] over the entire string, prefix and all.
 * An unparseable prefix costs a persisted line its timestamp; an unredacted
 * prefix that happened to carry a secret is a leak. Those costs are not
 * symmetric, so every ambiguous case resolves toward redacting more, not
 * less — this is a deliberate choice, not an oversight, should the next
 * reader wonder why the fallback exists at all.
 *
 * **An accepted residual.** A leading `label:` that survives *inside* the
 * message body — `stopTunnel:` in `TunnelService: stopTunnel: phase=...` — is
 * still redacted by [redact]'s own rules once the split hands it the body.
 * That is not chased here: the component name (the tag) and the phase value
 * both survive the split, which is what W7 diagnosis actually needs.
 */
private fun redactLine(line: String): String {
    val match = LOGCAT_PREFIX_PATTERN.matchEntire(line) ?: return redact(line)
    val prefix = match.groupValues[1]
    val body = match.groupValues[2]
    return prefix + redact(body)
}

/**
 * Joins [LogcatReader] → [redactLine] → [LogRing].
 *
 * **Spec §3.2: redaction happens here, at capture, not at display.** The reason
 * is at-rest exposure rather than display. A log file in `filesDir` carrying
 * server addresses, UUIDs and REALITY keys is a secret on disk that outlives
 * the session, survives into a device backup, and is readable by anyone with
 * the device unlocked — while a redact-on-display design leaves it there and
 * merely declines to show it. The most likely source is the config the core
 * quotes back when it rejects one (`XrayException`'s own KDoc warns about
 * exactly this), and that lands in the log path by default.
 *
 * ARCHITECTURE.md §5.6 is the invariant; this is the only place in the capture
 * path that enforces it, so a change here is a change to that invariant.
 */
internal class LogCapture(
    private val ring: LogRing,
    private val readerFactory: () -> LogcatReader = { LogcatReader() },
) {
    private var reader: LogcatReader? = null

    @Volatile
    private var running = false

    /**
     * Spec §3.3: capture is tied to session lifetime — started when the service
     * enters foreground, stopped in teardown, and stopped **last**, after the
     * teardown phases are logged.
     */
    fun start() {
        if (running) return
        running = true
        val r = readerFactory()
        reader = r
        thread(name = "subspace-log-capture", isDaemon = true) {
            captureOnce(r)
        }
    }

    fun stop() {
        running = false
        reader?.close()
        reader = null
    }

    /**
     * The pipeline itself, synchronous and without a thread, so a unit test can
     * assert the §5.6 guarantee without spawning anything.
     */
    internal fun captureOnce(lines: Sequence<String>) {
        for (line in lines) {
            ring.append(redactLine(line))
        }
    }

    /**
     * [captureOnce] over [reader]'s own lines, plus the [ABNORMAL_END_MARKER]
     * check once the sequence ends. Kept separate from the [Sequence] overload
     * above so that overload stays usable with a plain, reader-free sequence in
     * tests of the redaction pipeline itself.
     */
    internal fun captureOnce(reader: LogcatReader) {
        captureOnce(reader.lines())
        if (reader.endedWithError) {
            ring.append(redactLine(ABNORMAL_END_MARKER))
        }
    }
}
