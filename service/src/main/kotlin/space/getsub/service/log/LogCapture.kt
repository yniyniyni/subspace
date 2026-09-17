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
 * to disk. It is routed through [redact] like every other line so there is one
 * write path to the ring, not a second one that could drift — [redact] leaves
 * it untouched, but the call site is what matters, not the outcome.
 */
private const val ABNORMAL_END_MARKER = "log capture ended abnormally (stream read failed)"

/**
 * Joins [LogcatReader] → [redact] → [LogRing].
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
            ring.append(redact(line))
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
            ring.append(redact(ABNORMAL_END_MARKER))
        }
    }
}
