// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import android.util.Log
import space.getsub.core.model.redact
import java.time.ZoneId
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
 * Appended when a capture ended at its drain deadline rather than at its
 * sentinel ([LogcatReader.endedAtDeadline], review M-2): `logcat` was
 * signalled before this session's closing lines were seen, so they may be
 * missing. Informational, and not subject to D2's suppression — a deadline
 * stop is abnormal by definition, where a stop-caused read failure is not. A
 * literal with no detail, routed through [redactLine], for the same reasons
 * as [ABNORMAL_END_MARKER].
 */
private const val DEADLINE_END_MARKER = "log capture stopped at its drain deadline; closing lines may be missing"

/**
 * Appended when `logcat`'s stream ended cleanly with no stop requested
 * ([LogcatReader.endedUnexpectedly], review M-A, ruling R49): `logcat` exited
 * on its own and nothing after this point in the session was captured.
 * Informational, not subject to D2's suppression, a literal with no detail,
 * routed through [redactLine] — same reasons as [ABNORMAL_END_MARKER].
 */
private const val UNEXPECTED_END_MARKER = "log capture ended unexpectedly (logcat exited); later lines are missing"

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
 * — is dropped instead of written. So is a line `-T` replayed from before the
 * capture's start ([stampedBefore], R50): a drop, never an unredacted write.
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
 * @param zone the local zone `threadtime` stamps are printed in, read inside
 *   [start] for the replay filter ([stampedBefore], R50). A seam for tests.
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
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val startThread: (body: () -> Unit) -> Thread = { body ->
        thread(name = "subspace-log-capture", isDaemon = true) { body() }
    },
) {
    private var reader: LogcatReader? = null
    private var running = false

    /**
     * A started capture, for the next [start] to queue behind: its thread,
     * and the [System.nanoTime] by which its `logcat` will have spawned if
     * nothing is wedged — see [start].
     */
    private class Started(
        val thread: Thread,
        val spawnedByNanos: Long,
    )

    /**
     * The most recently started capture — possibly still queued, or draining
     * after its [stop]. The next [start]'s capture thread waits for it (see
     * [start]). Only ever joined from a capture thread.
     */
    private var lastCapture: Started? = null

    /**
     * Guards [running], [reader] and [lastCapture]. All three are read
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
     * **A start while the previous capture is still draining — serial readers
     * (N1 / R46, R50; R50 amends R48).** [stop] no longer ends the old
     * capture; it drains to its sentinel, bounded by its deadline. So a
     * reconnect can start a new capture while the old one is still writing.
     * The new capture thread first joins the old one, and only then spawns
     * its own `logcat`: at most one of this process's `logcat` readers runs
     * at a time. The join runs on the *new capture thread* — never this
     * method, never the teardown thread or the command coordinator (R18).
     *
     * *Why serial, when R48 spawned at once.* R48's early spawn existed only
     * because 6ea559a armed a queued capture's drain deadline at [stop], so a
     * capture queued behind its predecessor could be killed while its
     * `logcat` was still cold (review I-2 — mechanism (a) again). df87296
     * moved the arming to the later of the stop and the capture's own spawn
     * completing ([LogcatReader.requestStop]), which removed that risk and
     * left the early spawn as pure cost: it put every reader of a reconnect
     * burst into its cold start *together*, and on the Pixel 8 a `-T <epoch>`
     * reader's cold start grows with the number starting at once — measured
     * ~2.3 s for one, ~4.1 s for two, 6.4–8.6 s for four (N3 probe,
     * 2026-09-25). Four together blew the 5 s [DRAIN_DEADLINE_MILLIS], and a
     * burst of 4 fast reconnects captured 0 of 4 sessions, twice (N1 item 4);
     * the same concurrent start-up, while another process flooded the log,
     * coincided with a phone-wide liblog drop wave (N3). Serial, each reader
     * cold-starts alone — ~1.08 s with `-b main` (see [logcatProcessBuilder]).
     *
     * *Nothing the new session logs while it waits is lost:* its `-T` epoch
     * is read here, in [start], so its `logcat` replays everything logged
     * since — including, if it is stopped while still queued, its whole
     * session and its sentinel. Its deadline is armed only once it has
     * spawned (R48's rule, kept), so waiting in the queue never runs its
     * clock. Only a `:bg` death loses a queued session, as it would a
     * draining one.
     *
     * *The join's bound.* The old capture's `logcat` spawns by
     * [Started.spawnedByNanos] unless something is wedged; its stop came
     * before this start; so its deadline fires by the later of now and that,
     * plus [DRAIN_DEADLINE_MILLIS], and its thread has drained by
     * [DRAIN_MARGIN_MILLIS] after that. The join waits exactly that long.
     * The bound chains — a capture queued behind a queued capture inherits
     * its predecessor's — so serial order holds through a burst of any length
     * where every capture behaves. If the old thread outlives it anyway (a
     * spawn that hangs, ring writes blocked on disk), the new capture
     * proceeds rather than never capturing at all: two readers may then
     * overlap and the ring may interleave, which is the lesser loss.
     *
     * The old capture ends at its sentinel, which was logged before this
     * start's `-T` epoch was read, so the ring gets the old session's tail and
     * then the new session with no interleaving — I3's guarantee, kept across
     * the drain. What `-T` replays from before the epoch is dropped by stamp
     * ([stampedBefore]); a line stamped in the epoch's own millisecond is kept
     * and may be a duplicate — accepted over losing a real line (see
     * [logcatProcessBuilder]).
     */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val since = clock()
            val localZone = zone()
            val r = readerFactory(since)
            reader = r
            val prior = lastCapture
            val now = System.nanoTime()
            val waitUntilNanos =
                if (prior == null) {
                    now
                } else {
                    now + maxOf(0L, prior.spawnedByNanos - now) + PRIOR_END_MARGIN_NANOS
                }
            val t =
                startThread {
                    awaitPriorCapture(prior?.thread, waitUntilNanos)
                    captureOnce(r, since, localZone)
                }
            lastCapture = Started(t, waitUntilNanos + SPAWN_MARGIN_NANOS)
        }
    }

    /**
     * Ends the capture by draining it to a sentinel (N1 / R46–R48) — see
     * [LogcatReader]'s KDoc for the three loss mechanisms this closes.
     *
     * **Returns immediately — ruling R18.** Teardown must never wait on log
     * capture: nothing here joins, sleeps, or touches the reader's stream.
     * It records the stop, logs this capture's per-capture sentinel (after
     * every teardown line, since [TeardownStep.StopLogCapture] is last), and
     * arms a watchdog for [DRAIN_DEADLINE_MILLIS] — counted from the *later*
     * of this stop and `logcat`'s spawn completing (R48), so if `logcat` is
     * still spawning the reader arms it from the capture thread when the spawn
     * returns. The capture thread reads on until the sentinel, or until the
     * watchdog's [LogcatReader.forceStop] SIGTERMs `logcat` (R47) and the pipe
     * drains to EOF; the capture thread alone closes its reader (D3).
     *
     * **How long a capture outlives this call (review M-4).** The `logcat`
     * subprocess: at most [DRAIN_DEADLINE_MILLIS] after the later of this
     * stop and its spawn, plus however long it takes to exit on SIGTERM. The
     * capture thread: additionally until it has written out what is left in
     * the pipe — and a capture started while its predecessor was still
     * running first waits for it, before it spawns at all (R50, see
     * [start]). In a burst the waits chain, so the last capture of a burst
     * of N can outlive its stop by the drains of the N−1 before it plus its
     * own: typically ~1 s each, and at worst [DRAIN_DEADLINE_MILLIS] plus
     * the margins each. None of that is on this thread.
     *
     * Synchronized with [start] for the reason its KDoc gives (I3).
     */
    fun stop() {
        synchronized(lock) {
            running = false
            val r = reader ?: return
            reader = null
            r.requestStop { runCatching { armDeadline(DRAIN_DEADLINE_MILLIS) { r.forceStop() } } }
            runCatching { emitSentinel(r.sentinel) }
        }
    }

    /**
     * Runs on the new capture thread only, before its `logcat` is spawned
     * (R50). A join bounded by [untilNanos] — see [start] for how the bound is
     * derived: the prior capture ends at its sentinel or, at worst, shortly
     * after its deadline.
     */
    private fun awaitPriorCapture(
        prior: Thread?,
        untilNanos: Long,
    ) {
        if (prior == null) return
        val remaining = untilNanos - System.nanoTime()
        if (remaining > 0) runCatching { TimeUnit.NANOSECONDS.timedJoin(prior, remaining) }
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
     * [captureOnce] over [reader]'s own lines, plus the [ABNORMAL_END_MARKER],
     * [DEADLINE_END_MARKER] and [UNEXPECTED_END_MARKER] checks once the
     * sequence ends. Kept separate
     * from the [Sequence] overload above so that overload stays usable with a
     * plain, reader-free sequence in tests of the redaction pipeline itself.
     *
     * @param sinceEpochMillis this capture's `-T` epoch. Lines stamped before
     *   it are replays and are dropped ([stampedBefore], R50) — before
     *   redaction, which every kept line still goes through unchanged. Null:
     *   no stamp filter. The markers are never filtered.
     * @param localZone the zone the `threadtime` stamps are in.
     */
    internal fun captureOnce(
        reader: LogcatReader,
        sinceEpochMillis: Long? = null,
        localZone: ZoneId = ZoneId.systemDefault(),
    ) {
        val lines = reader.lines()
        captureOnce(
            if (sinceEpochMillis == null) {
                lines
            } else {
                lines.filterNot { stampedBefore(it, sinceEpochMillis, localZone) }
            },
        )
        if (reader.endedWithError) {
            ring.append(redactLine(ABNORMAL_END_MARKER))
        }
        if (reader.endedAtDeadline) {
            ring.append(redactLine(DEADLINE_END_MARKER))
        }
        if (reader.endedUnexpectedly) {
            ring.append(redactLine(UNEXPECTED_END_MARKER))
        }
    }

    internal companion object {
        private const val TAG = "LogCapture"

        /**
         * How long a stopped capture may drain before the watchdog SIGTERMs
         * `logcat` (N1 / R46, R47), counted from the later of the stop and the
         * spawn completing (R48). Measured on a Pixel 8: a freshly spawned
         * `logcat` delivers nothing for ~3.9 s with `-T 1`, ~1.9–2.4 s with
         * `-T <epoch>`, and ~1.08 s with `-T <epoch> -b main` — without losing
         * what was logged meanwhile — while a warm one delivers within ~10 ms.
         * 5 s clears a *single* reader's worst measured startup delay, so
         * even a session stopped the moment it started drains in full; a warm
         * capture reaches its sentinel in milliseconds and never gets near
         * this. It does not clear several readers starting together (6.4–8.6 s
         * for four) — which is why readers are serial (R50, see [start]).
         */
        const val DRAIN_DEADLINE_MILLIS = 5_000L

        /** How long past its deadline a SIGTERM'd capture may take to drain its pipe and exit. */
        const val DRAIN_MARGIN_MILLIS = 1_000L

        /** How long a capture's spawn — `sh` plus `exec logcat` — may take once it stops waiting. */
        const val SPAWN_MARGIN_MILLIS = 1_000L

        private val PRIOR_END_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(DRAIN_DEADLINE_MILLIS + DRAIN_MARGIN_MILLIS)
        private val SPAWN_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(SPAWN_MARGIN_MILLIS)
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
