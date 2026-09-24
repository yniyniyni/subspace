// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import android.util.Log
import space.getsub.core.model.redact
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 *
 * The tag group is `[^:\n\r]*`, not the simpler `[^:]*` — a negated character
 * class matches a line terminator regardless of whether `DOTALL` is set, so
 * `[^:]*` would let the tag run past an embedded `\n` or `\r` and swallow
 * real content — a secret included — into the prefix this pattern preserves
 * verbatim. [LogcatReader.lines] never hands this a line with an embedded
 * terminator (`BufferedReader.readLine()` strips every one), but
 * `captureOnce(Sequence<String>)` is a seam that accepts arbitrary strings,
 * and this is the single enforcement point for ARCHITECTURE.md §5.6 — a
 * safety property that held only because of what some other class happened
 * to do would not be a property. Excluding both explicitly means a line
 * shaped like the prefix but carrying a stray terminator fails to match at
 * all and falls back to whole-line [redact] instead, which is the safe
 * direction: an over-redacted multi-line payload, never an under-redacted
 * one.
 *
 * **The assumption this whole split rests on:** every tag captured through
 * this pattern is a compile-time constant — this codebase's own `TAG` vals
 * (`TunnelService`, `TunnelClient`, `BootReceiver`, `SubscriptionSyncer`,
 * `SubspaceApplication`, …) or a bundled native library's fixed `LOG_TAG`
 * (`subspace-tun2socks`) — never a value built from user or network data.
 * That is what makes preserving the prefix verbatim safe. Nothing enforces
 * it: the tag group is otherwise unbounded and admits spaces, so a future
 * `Log.w(someHostname, …)` anywhere in `:service` or `:core` would have that
 * hostname preserved on disk, and no test here would catch it. There is no
 * code fix for that — it is a property of how `Log` calls are written, not
 * of this pattern — so this note exists to make the assumption visible to
 * whoever next adds one.
 */
private val LOGCAT_PREFIX_PATTERN =
    Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+[^:\s\n\r][^:\n\r]*:\s*)(.*)$""")

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
 * That is not chased here: the component name (the tag) survives the split.
 *
 * **The phase value does not survive for free.** A device capture on
 * 2026-09-16 showed [redact]'s own positional rules eating it: a body of the
 * shape `tun2socks.stop exit +33ms` is a dotted identifier —
 * `HOSTNAME_PATTERN` in `Redaction.kt` — and a leading `word:` — like the old
 * `teardown: tun2socks.stop enter` — is `BARE_HOST_PREFIX_PATTERN`'s bare-host
 * shape. Both eat cleanly, leaving only durations behind. There is no fix for
 * this here: [redact] must keep catching those shapes everywhere else they
 * mean a real host, so the constraint sits on the emission side. `TunnelService`'s
 * teardown instrumentation follows the convention `"teardown[<phase>] <event>"`
 * — bracketed, undotted — specifically so its phase names fall outside every
 * pattern in `Redaction.kt`; see the comment above that block, and
 * `RedactionTest`'s `teardown phase names survive redaction` test, before
 * changing that shape or adding a new phase line elsewhere.
 */
private fun redactLine(line: String): String {
    val match = LOGCAT_PREFIX_PATTERN.matchEntire(line) ?: return redact(line)
    val prefix = match.groupValues[1]
    val body = match.groupValues[2]
    return prefix + redact(body)
}

/**
 * Where [LogCapture] writes each redacted line. [LogRing] in production; a
 * seam so a test can hold the capture thread inside a write — the state N1
 * mechanism (c) needs, a capture thread behind the pipe because redaction
 * costs ~1.3 ms a line on device.
 */
internal fun interface LineSink {
    fun append(line: String)
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
 * path that enforces it, so a change here is a change to that invariant. The
 * drain-to-sentinel stop (N1 / R46, see [LogcatReader]) does not add a second
 * write path: every line drained after [stop] goes through [redactLine] like
 * every other; only the sentinel itself — matched raw, in [LogcatReader.lines]
 * — is dropped instead of written.
 *
 * @param readerFactory builds one [LogcatReader] per capture, following
 *   `logcat` from the given wall-clock epoch (ms) — see [logcatProcessBuilder].
 * @param emitSentinel logs a capture's sentinel from `:bg`, where `logcat`
 *   will see it after every line logged before it. `android.util.Log` in
 *   production; a seam for JVM tests.
 * @param armDeadline schedules the drain watchdog: calls its callback once,
 *   after the given delay (ms), on some thread other than the caller's. Must
 *   not block. A seam so tests can fire the deadline by hand.
 * @param clock wall-clock milliseconds, read inside [start] for `-T`.
 * @param startThread starts a capture thread running the given body. A seam
 *   so tests can join the threads they drive.
 */
@Suppress("LongParameterList") // Every parameter past the first is a test seam with a production default.
internal class LogCapture(
    private val ring: LineSink,
    private val readerFactory: (sinceEpochMillis: Long) -> LogcatReader = { since ->
        LogcatReader { spawnLogcat(since) }
    },
    private val emitSentinel: (String) -> Unit = { sentinel -> Log.i(TAG, sentinel) },
    private val armDeadline: (delayMillis: Long, onDeadline: () -> Unit) -> Unit = ::armWatchdog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val startThread: (body: () -> Unit) -> Thread = { body ->
        thread(name = "subspace-log-capture", isDaemon = true) { body() }
    },
) {
    private var reader: LogcatReader? = null
    private var running = false

    /**
     * The most recently started capture thread — possibly still draining
     * after its [stop]. The next [start]'s capture thread waits for it (see
     * [start]). Only ever joined from a capture thread.
     */
    private var lastCaptureThread: Thread? = null

    /**
     * Guards [running], [reader] and [lastCaptureThread]. All three are read
     * and written exclusively inside a `synchronized(lock)` block — see
     * [start]'s KDoc — so `@Volatile` is not needed on top of it; the lock
     * already gives every reader the writer's happens-before guarantee.
     */
    private val lock = Any()

    /**
     * Spec §3.3: capture is tied to session lifetime — started when the service
     * enters foreground, stopped in teardown, and stopped **last**, after the
     * teardown phases are logged.
     *
     * **Synchronized with [stop] — do not remove this lock (I3).** [start] is only
     * ever called through `TunnelCommandCoordinator` (`TunnelService.kt:1373`),
     * but [stop] is also reached from `onRevoke()` and `onDestroy()`
     * (`TunnelService.kt`), both of which call it **directly**, bypassing the
     * coordinator — the same fact `tun2socks_jni.c`'s own locking-contract
     * comment cites: "§5.4 says disconnect, onRevoke, and onDestroy are not
     * serialised with each other". Without a lock here, a `stop()` and a
     * `start()` can interleave as `running = false` / read `reader` (A) /
     * `if (running) return` sees false and proceeds (B) / `running = true`,
     * `reader = <new>` (B) / `reader = null` (A) — leaving `running == true`
     * with `reader == null`. The reader B just created is then orphaned: no
     * later `stop()` can reach it through the null field, so its `logcat`
     * subprocess and capture thread run for the rest of the process's life,
     * and the *next* `start()` spawns a third reader — from then on two
     * capture threads append every line to the ring twice, silently, in the
     * log this milestone exists to make trustworthy. `synchronized` around
     * these two short, non-blocking bodies closes that window; it is not the
     * `cancelAndJoin`-style teardown block ruling R18 forbids, and must stay
     * that way — do not add a wait or a join inside either critical section.
     *
     * **A start while the previous capture is still draining (N1 / R46).**
     * [stop] no longer ends the old capture; it drains to its sentinel for up
     * to [DRAIN_DEADLINE_MILLIS]. So a reconnect can start a new capture while
     * the old one is still writing. The *new capture thread* — never this
     * method, never the teardown thread or the command coordinator — joins the
     * old one, bounded by [PRIOR_DRAIN_WAIT_MILLIS], before it spawns
     * `logcat` or writes anything. The old capture ends at its sentinel, which
     * was logged before this start's `-T` epoch was read, so the ring gets the
     * old session's tail and then the new session: no duplicates, no
     * interleaving — I3's guarantee, kept across the drain. If the old thread
     * outlives even that bound (a `logcat` that survives `destroy()`), the
     * new capture proceeds rather than never capturing at all; the two could
     * then interleave, which is the lesser loss.
     */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val since = clock()
            val r = readerFactory(since)
            reader = r
            val prior = lastCaptureThread
            lastCaptureThread =
                startThread {
                    awaitPriorDrain(prior)
                    captureOnce(r)
                }
        }
    }

    /**
     * Ends the capture by draining it to a sentinel (N1 / R46) — see
     * [LogcatReader]'s KDoc for the three loss mechanisms this closes.
     *
     * **Returns immediately — ruling R18.** Teardown must never wait on log
     * capture: nothing here joins, sleeps, or touches the reader's stream.
     * It records the stop, logs this capture's per-capture sentinel (after
     * every teardown line, since [TeardownStep.StopLogCapture] is last), and
     * arms a watchdog for [DRAIN_DEADLINE_MILLIS]. The capture thread reads
     * on until the sentinel, or until the watchdog's [LogcatReader.forceStop]
     * destroys `logcat` — which only destroys; the capture thread alone
     * closes its reader (D3).
     *
     * Synchronized with [start] for the reason its KDoc gives (I3).
     */
    fun stop() {
        synchronized(lock) {
            running = false
            val r = reader ?: return
            reader = null
            r.requestStop()
            runCatching { emitSentinel(r.sentinel) }
            runCatching { armDeadline(DRAIN_DEADLINE_MILLIS) { r.forceStop() } }
        }
    }

    /**
     * Runs on the new capture thread only. A bounded join: the prior capture
     * ends at its sentinel or, at worst, shortly after its deadline.
     */
    private fun awaitPriorDrain(prior: Thread?) {
        if (prior == null) return
        runCatching { prior.join(PRIOR_DRAIN_WAIT_MILLIS) }
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

    internal companion object {
        private const val TAG = "LogCapture"

        /**
         * How long a stopped capture may drain before the watchdog destroys
         * `logcat` (N1 / R46). Measured on a Pixel 8: a freshly spawned
         * `logcat` delivers nothing for ~3.9 s with `-T 1` and ~1.9 s with
         * `-T <epoch>` — without losing what was logged meanwhile — while a
         * warm one delivers within ~10 ms. 5 s clears the worst measured
         * startup delay, so even a session stopped the moment it started
         * drains in full; a warm capture reaches its sentinel in milliseconds
         * and never gets near this.
         */
        const val DRAIN_DEADLINE_MILLIS = 5_000L

        /**
         * The next capture thread's bound on waiting for the previous one
         * (see [start]): the drain deadline, plus a margin for the post-destroy
         * drain of whatever was still in the pipe.
         */
        const val PRIOR_DRAIN_WAIT_MILLIS = DRAIN_DEADLINE_MILLIS + 1_000L
    }
}

/**
 * One idle-timeout daemon thread for every drain deadline in the process:
 * [ScheduledThreadPoolExecutor.schedule] only enqueues, so arming it from
 * [LogCapture.stop] cannot block teardown (R18), and the thread exits when
 * there is nothing left to time.
 */
private val watchdog: ScheduledThreadPoolExecutor by lazy {
    ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "subspace-log-drain-watchdog").apply { isDaemon = true }
    }.apply {
        setKeepAliveTime(WATCHDOG_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }
}

private const val WATCHDOG_KEEP_ALIVE_SECONDS = 30L

private fun armWatchdog(
    delayMillis: Long,
    onDeadline: () -> Unit,
) {
    watchdog.schedule({ runCatching(onDeadline) }, delayMillis, TimeUnit.MILLISECONDS)
}
