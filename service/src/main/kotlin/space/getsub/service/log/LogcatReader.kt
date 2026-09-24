// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import android.system.Os
import android.system.OsConstants
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * The constant half of every capture's end-of-drain sentinel (N1 / R46). The
 * other half is a per-reader random nonce — see [LogcatReader.sentinel]. The
 * line carries nothing else: no config, no session detail (§5.6).
 */
internal const val SENTINEL_PREFIX = "subspace-log-capture-end"

/**
 * Owns a `logcat` subprocess and emits its lines.
 *
 * Spec §3.1: this exists rather than an in-process log facade because
 * xray-core's `GoLog` output and hev's `subspace-tun2socks` output are native
 * lines no Kotlin facade can see — and those are exactly the lines that explain
 * why a dial failed. `:main` and `:bg` share a UID, so one reader sees both.
 *
 * Knows nothing about redaction or files. [LogCapture] joins the three.
 *
 * **How a capture ends — drain to a sentinel (N1, rulings R46–R48).** On a
 * Pixel 8 the teardown's final `teardown[lifecycle] done` line reached the
 * ring in 0 of 14 stops, and sessions shorter than ~1.5 s captured nothing,
 * because the old stop *killed* `logcat` and closed this reader on the
 * teardown thread: (a) a freshly spawned `logcat` delivers nothing for
 * ~1.9–3.9 s, so a short session's `logcat` died before delivering anything;
 * (b) `done` is logged microseconds before the stop, the one window where
 * even a warm `logcat` (which delivers within ~10 ms) has not written it;
 * (c) lines already in the pipe were thrown away by the close, because the
 * capture thread is often behind (redaction costs ~1.3 ms a line). So a stop
 * no longer ends anything here directly:
 *
 * 1. [requestStop] records that a stop was asked for, and arms the deadline
 *    once `logcat` has also spawned (R48 — see there). [LogCapture.stop] then
 *    logs [sentinel] through `android.util.Log`, *after* every teardown line,
 *    and returns immediately (R18).
 * 2. [lines] — on the capture thread — keeps reading until it sees its own
 *    [sentinel] on a raw line, then cleans up: destroys the subprocess and
 *    closes its own reader.
 * 3. If the sentinel never arrives, the watchdog calls [forceStop] at the
 *    deadline, which **SIGTERMs `logcat` by PID and never calls
 *    [Process.destroy]** (R47). `logcat` exits cleanly; whatever it wrote
 *    stays in the pipe; our end of the pipe stays open; the capture thread
 *    drains to EOF and cleans up itself.
 *
 * **Why not `Process.destroy()` at the deadline (review I-1, ruling R47).**
 * R46 assumed `destroy()` only makes the pipe hit EOF. On Android it does not:
 * `UNIXProcess.destroy()` (`sources/android-36.1/java/lang/UNIXProcess.java:221-234`)
 * kills the process and then closes stdin/stdout/stderr — *our* end of the
 * pipe. Everything still unread (up to 64 KiB in the kernel pipe, plus the
 * 8 KiB `BufferedInputStream` buffer) is discarded, and the capture thread's
 * next read throws `IOException("Stream closed")` — which D2 then, correctly
 * for a stop, declines to call an error, so the loss was silent. A SIGTERM
 * leaves our end alone: measured on the device, `logcat` SIGTERM'd even 0 ms
 * after a line was logged still delivered it.
 *
 * **No thread other than the capture thread ever closes the reader.** That is
 * what keeps D3's deadlock from coming back: `BufferedReader.readLine()` and
 * `BufferedReader.close()` share a monitor, and a close from any other thread
 * while a read is in flight blocks until that read returns — which, on a quiet
 * device, can be never. See [forceStop].
 *
 * **Known residual:** if `:bg` is killed before the drain completes, the tail
 * is lost with it. Nothing in-process can outlive the process.
 *
 * @param signal SIGTERMs a PID. Production uses `Os.kill`; a seam for JVM
 *   tests, where `android.system.Os` is a stub.
 * @param spawn a seam for tests. Production uses [spawnLogcat]. Its stdout's
 *   first line must be the PID to [signal] — see [logcatProcessBuilder].
 */
internal class LogcatReader(
    private val signal: (pid: Int) -> Unit = { pid -> Os.kill(pid, OsConstants.SIGTERM) },
    private val spawn: () -> Process,
) {
    /**
     * This capture's end marker: [SENTINEL_PREFIX] plus a random 128-bit
     * nonce, so a line can only match if *this* capture's stop logged it —
     * never another capture's sentinel, and never a line some other component
     * happened to write. Matched on the raw line, before redaction, and never
     * emitted from [lines].
     */
    val sentinel: String = "$SENTINEL_PREFIX ${UUID.randomUUID().toString().replace("-", "")}"

    /** Guarded by [publishLock]. */
    private var process: Process? = null

    /** `logcat`'s PID, from the shell's first line (R47). Guarded by [publishLock]. */
    private var pid: Int? = null

    /** True once [spawn] has returned (successfully or not). Guarded by [publishLock]. */
    private var spawnDone = false

    /** [requestStop]'s deadline arming, deferred until [spawnDone] (R48). Guarded by [publishLock]. */
    private var armDeadlineOnSpawn: (() -> Unit)? = null

    @Volatile
    var endedWithError: Boolean = false
        private set

    @Volatile
    private var reachedSentinel: Boolean = false

    /**
     * True when the watchdog's deadline fired before this capture reached its
     * sentinel — the closing lines may be missing. [LogCapture] writes a
     * marker for it. Unlike a stop-caused read failure (D2, below), a deadline
     * stop is abnormal by definition, so nothing suppresses this (review M-2).
     */
    val endedAtDeadline: Boolean get() = deadlinePassed && !reachedSentinel

    /**
     * True when `logcat`'s stream reached a clean EOF while no stop had been
     * requested — `logcat` exited on its own mid-session (review M-A, ruling
     * R49), for whatever reason: among the possibilities, logd dropping a
     * reader that fell too far behind under heavy logging. [LogCapture]
     * writes a marker for it so the ring is not silently truncated. Like
     * [endedAtDeadline], not subject to D2's suppression: D2 is about failures
     * a stop *causes*, and here no stop was requested. An EOF after a
     * requested stop (SIGTERM at the deadline, or `logcat` exiting before its
     * sentinel arrived) never sets it, and the sentinel path ends before any
     * EOF is read.
     */
    @Volatile
    var endedUnexpectedly: Boolean = false
        private set

    /**
     * Set by [requestStop] (and by [forceStop]) — D2 (device verification,
     * 2026-09-20), carried over to R46.
     *
     * D2's original shape: the teardown thread closed this reader while the
     * capture thread might have a `readLine()` in flight, and the read failure
     * that followed was the closed stream behaving as a closed stream should,
     * not evidence the subprocess or pipe broke. Under R46/R47 nothing but the
     * capture thread closes the reader, and the deadline no longer closes the
     * stream either — but [forceStop]'s last-resort `destroy()` (PID unknown)
     * still can, so a read failure after a requested stop is still expected
     * and must not report a stream failure that never happened. A failure with
     * no stop requested still sets [endedWithError]. What a deadline stop
     * *does* report is [endedAtDeadline].
     */
    @Volatile
    private var stopRequested: Boolean = false

    /**
     * Set by [forceStop]: the drain deadline has passed. A process [lines]
     * publishes afterwards is destroyed at once and never read — F3's
     * guarantee. (Under R48 the deadline is only armed once spawn has
     * completed, so through [LogCapture] this ordering cannot arise; it guards
     * [forceStop]'s own contract.)
     */
    @Volatile
    private var deadlinePassed: Boolean = false

    /**
     * Guards [process], [pid], [spawnDone] and [armDeadlineOnSpawn] against
     * [requestStop]/[forceStop] — F3 (review, 2026-09-22). [spawn] itself runs
     * outside this lock (see [lines]): it can take arbitrarily long (launching
     * a subprocess), and R18 forbids teardown ever waiting on log capture, so
     * the watchdog must never be able to block behind it.
     *
     * What the lock closes is the gap *between* spawn returning and this
     * instance publishing the process it created. F3's original finding: a
     * stop running in that gap saw no process, destroyed nothing, and
     * returned — then [lines] published the process anyway and read it
     * forever, orphaning a subprocess nothing referenced any more.
     *
     * **How F3 changed shape under R46/R48.** A *requested* stop that lands
     * during spawn no longer means "destroy whatever spawn produces": that was
     * N1 mechanism (a), a short session losing everything. It now means
     * "drain to the sentinel", and the deadline that bounds the drain is armed
     * when the spawn completes — so the leak is still bounded, by the deadline
     * counted from spawn rather than from stop.
     *
     * Every critical section that holds this lock is short and non-blocking —
     * field writes, [Process.exitValue], a `kill(2)`, and [Process.destroy]
     * (`kill` plus three unsynchronized stream closes on Android) — so holding
     * it is not the wait R18 forbids.
     */
    private val publishLock = Any()

    /**
     * Blocking, and consumed on a dedicated thread — this follows the
     * subprocess until it delivers [sentinel], reaches EOF (after [forceStop]
     * or on its own), or a read fails.
     *
     * Called at most once per instance — the capture thread is its only
     * caller, and therefore the only thread that ever closes the reader.
     *
     * Spawns eagerly, when called. The first line is the shell's PID (R47),
     * consumed and never emitted; a first line that is not a PID means the
     * shell never reached `exec logcat`, and takes the spawn-failure path.
     * [beforeReading] then runs — still on the capture thread — before the
     * first `logcat` line is read: [LogCapture] holds it until the previous
     * capture has finished writing (R48), while this `logcat` warms up.
     *
     * Any line carrying [SENTINEL_PREFIX] is dropped: it is capture plumbing,
     * not diagnostic content. Only this reader's own [sentinel] ends the
     * sequence; another capture's (which a `-T <epoch>` landing on the same
     * millisecond as the previous session's stop could replay) is skipped.
     *
     * A spawn failure yields an empty sequence rather than throwing: capture is
     * a diagnostic, and ARCHITECTURE.md §10.4's rule about failing loudly is
     * about the *start sequence*, not about a logger that could otherwise abort
     * one.
     */
    fun lines(beforeReading: () -> Unit = {}): Sequence<String> {
        val proc = spawnAndPublish() ?: return emptySequence()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        return sequence {
            try {
                if (readPid(reader)) {
                    beforeReading()
                    yieldAll(
                        generateSequence { readOrNull(reader) }
                            .onEach { if (sentinel in it) reachedSentinel = true }
                            .takeWhile { sentinel !in it }
                            .filterNot { SENTINEL_PREFIX in it },
                    )
                }
            } finally {
                finish(reader)
            }
        }
    }

    /**
     * Spawns `logcat` and publishes it for [forceStop], or — if the deadline
     * has already passed — destroys it unread and returns null. Also null on
     * a spawn failure. Runs any deadline arming [requestStop] deferred (R48).
     */
    private fun spawnAndPublish(): Process? {
        val proc = runCatching { spawn() }.getOrNull()
        var arm: (() -> Unit)? = null
        val published =
            synchronized(publishLock) {
                spawnDone = true
                arm = armDeadlineOnSpawn.takeIf { proc != null }
                armDeadlineOnSpawn = null
                // F3: forceStop() either ran before this block (deadlinePassed
                // is visible here — both sides hold publishLock) or runs after
                // it and finds the process to act on. There is no third order.
                val ok = proc != null && !deadlinePassed
                if (ok) process = proc
                ok
            }
        if (proc != null && !published) {
            // The deadline has passed: nothing else can reach this process,
            // so this branch must be the one that ends it. Never read, so
            // destroy()'s discard of the pipe (see the class KDoc) costs nothing.
            runCatching { proc.destroy() }
            runCatching { proc.inputStream.close() }
        }
        if (published) arm?.let { runCatching(it) }
        return proc.takeIf { published }
    }

    /** Consumes the shell's PID line (R47). False — a failed spawn — if it is missing or not a PID. */
    private fun readPid(reader: BufferedReader): Boolean {
        val parsed =
            runCatching { reader.readLine() }.getOrNull()
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        synchronized(publishLock) { pid = parsed }
        return parsed != null
    }

    /**
     * One read; a failure ends the sequence, and is an error only if no stop
     * was requested (D2). A clean EOF with no stop requested is recorded as
     * [endedUnexpectedly] (R49) — decided at the moment of EOF, so a stop that
     * arrives afterwards cannot hide it.
     */
    private fun readOrNull(reader: BufferedReader): String? =
        runCatching { reader.readLine() }
            .onFailure { if (!stopRequested) endedWithError = true }
            .onSuccess { if (it == null && !stopRequested) endedUnexpectedly = true }
            .getOrNull()

    /**
     * Runs on the capture thread only, once [lines] is done reading. Destroys
     * the subprocess, then closes the reader — D3's order.
     *
     * **`destroy()` is safe here only because nothing more will be read.** On
     * Android it discards whatever is unread in the pipe (see the class KDoc),
     * and at this point the sequence has ended at the sentinel, at EOF, or on
     * a read failure. It is what reaps the shell-turned-`logcat` after a
     * sentinel stop, when `logcat` is still running. The same reasoning covers
     * the two other `destroy()` calls in this class (a spawn published after
     * the deadline, never read; [forceStop]'s PID-unknown fallback, before
     * anything past the PID line was read). Do not copy it onto a path that
     * still intends to read — that is exactly review I-1.
     */
    private fun finish(reader: BufferedReader) {
        synchronized(publishLock) {
            runCatching { process?.destroy() }
            process = null
        }
        runCatching { reader.close() }
    }

    /**
     * Records that a stop was requested and arms the drain deadline via
     * [armDeadline] — now if `logcat` has already spawned, otherwise the
     * moment it does (R48: a capture's deadline runs from the *later* of its
     * stop and its spawn, so a `logcat` still spawning is not killed cold).
     * If the spawn fails, nothing is armed: there is nothing to stop.
     *
     * Does not destroy, close, or wait on anything, and [armDeadline] must
     * only schedule: returns immediately (R18).
     */
    fun requestStop(armDeadline: () -> Unit = {}) {
        val armNow =
            synchronized(publishLock) {
                stopRequested = true
                if (spawnDone) {
                    process != null
                } else {
                    armDeadlineOnSpawn = armDeadline
                    false
                }
            }
        if (armNow) armDeadline()
    }

    /**
     * The watchdog's call, at the drain deadline. **SIGTERMs `logcat` by PID;
     * never calls [Process.destroy], and never closes the reader** (R47).
     *
     * Not `destroy()`: on Android it closes our end of the pipe and discards
     * everything unread — review I-1; see the class KDoc.
     *
     * Not `reader.close()`: D3 (2026-09-21). `BufferedReader.readLine()` and
     * `BufferedReader.close()` serialize on the same monitor, and a thread
     * blocked inside a native read holds it for the whole call. The capture
     * thread is very likely sitting in exactly that read when the deadline
     * fires, so a close from here would wait for that read to return — and
     * teardown once wedged on precisely that shape (`jstack`: the capture
     * thread `locked` inside `StreamDecoder.readBytes`, another thread
     * `BLOCKED` in `BufferedReader.close()`).
     *
     * The signal is sent only while the process is still running —
     * [Process.exitValue] throwing `IllegalThreadStateException` — which is
     * the same PID-reuse guard the platform's own `destroy()` applies: once
     * the process has been reaped its PID may belong to something else.
     *
     * **Last resort:** if the PID is not known yet — the shell has not printed
     * its first line a full deadline after spawning — there is nothing to
     * signal and nothing past the PID line has been read, so this falls back
     * to `destroy()` to keep the leak bounded.
     *
     * Also arms the F3 guard: a process [spawn] publishes after this has run
     * is destroyed at once and never read.
     */
    fun forceStop() {
        synchronized(publishLock) {
            stopRequested = true
            deadlinePassed = true
            val proc = process ?: return
            val knownPid = pid
            when {
                knownPid == null -> runCatching { proc.destroy() }
                isRunning(proc) -> runCatching { signal(knownPid) }
            }
        }
    }

    private fun isRunning(proc: Process): Boolean =
        runCatching { proc.exitValue() }.exceptionOrNull() is IllegalThreadStateException
}

/**
 * The shell script [logcatProcessBuilder] runs (R47). `echo $$` prints the
 * shell's own PID; `exec` replaces the shell with `logcat` *under that same
 * PID*, so the first line of stdout is `logcat`'s PID — which Android's
 * `Process` does not expose publicly. `"$@"` passes the remaining argv
 * elements through as-is: the shell never re-parses or re-splits them.
 */
private const val LOGCAT_VIA_SHELL = "echo \$\$; exec logcat \"\$@\""

/**
 * `logcat`'s argument list, exposed separately from [spawnLogcat] so a test
 * can assert on it — `ProcessBuilder.command()` just returns the list back,
 * no process spawned — without needing `logcat` to exist in a JVM test
 * environment.
 *
 * Spawned through `sh -c` (see [LOGCAT_VIA_SHELL]) so the watchdog can SIGTERM
 * `logcat` by PID rather than calling `Process.destroy()` (R47). The `sh`
 * after the script is `$0`; `logcat`'s own arguments follow as separate list
 * elements, exactly as before.
 *
 * `-v threadtime` for a stable, parseable prefix; no `-d`, so it follows.
 *
 * **`-T <epoch>`, where the epoch is [LogCapture.start]'s wall-clock reading
 * (N1 / R46; replaces I2's `-T 1`).** Without a `-T`/`-t`, `logcat` dumps this
 * UID's entire retained buffer before it starts following — review finding
 * I2, confirmed on device: a ring the spec (§3.5) calls a record of *one
 * session* began with `--------- beginning of main` and a pre-session `:main`
 * line. I2's fix, `-T 1`, still replayed one line of history — in practice
 * always the previous session's last line, which after N1 was the very `done`
 * line that session had lost. `-T '<seconds>.<millis>'` (accepted by the
 * Pixel 8's `logcat`, measured 2026-09) replays nothing from before the
 * session's start millisecond, follows without implying `-d`, and delivered
 * its first line in ~1.9 s against ~3.9 s for `-T 1`.
 *
 * **One accepted duplicate (review M-1).** `-T` has millisecond granularity
 * while logd timestamps are finer, so a line logged in the same millisecond
 * the epoch was read — e.g. the previous session's last lines, if its stop and
 * this start share a millisecond, or after the wall clock steps backwards —
 * can be replayed here and appear twice in the ring. Accepted: rounding the
 * other way would risk losing a real line of this session, which is worse.
 *
 * **Not the filter-narrowing ruling R30 refused.** R30 declined to drop
 * *diagnostic* lines a live session produces. The epoch loses no line the
 * session produces — it only declines to re-read history that predates it.
 *
 * Deliberately unfiltered by tag: the whole point is to catch output from
 * libraries whose tags this app does not choose.
 */
internal fun logcatProcessBuilder(sinceEpochMillis: Long): ProcessBuilder =
    ProcessBuilder("sh", "-c", LOGCAT_VIA_SHELL, "sh", "-v", "threadtime", "-T", logcatEpoch(sinceEpochMillis))
        .redirectErrorStream(true)

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_DIGITS = 3

/** `logcat -T`'s `<seconds>.<millis>` form of an epoch in milliseconds. */
private fun logcatEpoch(epochMillis: Long): String =
    "${epochMillis / MILLIS_PER_SECOND}.${(epochMillis % MILLIS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0')}"

internal fun spawnLogcat(sinceEpochMillis: Long): Process = logcatProcessBuilder(sinceEpochMillis).start()
