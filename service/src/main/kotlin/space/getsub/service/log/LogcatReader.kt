// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

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
 * **How a capture ends — drain to a sentinel (N1, ruling R46).** On a Pixel 8
 * the teardown's final `teardown[lifecycle] done` line reached the ring in 0
 * of 14 stops, and sessions shorter than ~1.5 s captured nothing, because the
 * old stop *killed* `logcat` and closed this reader on the teardown thread:
 * (a) a freshly spawned `logcat` delivers nothing for ~1.9–3.9 s, so a short
 * session's `logcat` died before delivering anything; (b) `done` is logged
 * microseconds before the stop, the one window where even a warm `logcat`
 * (which delivers within ~10 ms) has not written it; (c) lines already in the
 * pipe were thrown away by the close, because the capture thread is often
 * behind (redaction costs ~1.3 ms a line). So a stop no longer ends anything
 * here directly:
 *
 * 1. [requestStop] only records that a stop was asked for. [LogCapture.stop]
 *    then logs [sentinel] through `android.util.Log`, *after* every teardown
 *    line, and returns immediately (R18).
 * 2. [lines] — on the capture thread — keeps reading until it sees its own
 *    [sentinel] on a raw line, then destroys the subprocess and closes its
 *    own reader.
 * 3. If the sentinel never arrives, the watchdog calls [forceStop] at
 *    [LogCapture.DRAIN_DEADLINE_MILLIS], which **only destroys the
 *    subprocess**. The pipe then hits EOF; the capture thread drains what is
 *    left, reads null, and closes its own reader.
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
 * @param spawn a seam for tests. Production uses [spawnLogcat].
 */
internal class LogcatReader(
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

    @Volatile
    private var process: Process? = null

    @Volatile
    var endedWithError: Boolean = false
        private set

    /**
     * Set by [requestStop] (and by [forceStop]) — D2 (device verification,
     * 2026-09-20), carried over to R46.
     *
     * D2's original shape: the teardown thread closed this reader while the
     * capture thread might have a `readLine()` in flight, and the read failure
     * that followed was the closed stream behaving as a closed stream should,
     * not evidence the subprocess or pipe broke. Under R46 nothing but the
     * capture thread closes the reader, but a stop still *causes* the stream
     * to end — the watchdog's [Process.destroy] can surface on the reading
     * side as a read failure rather than a clean EOF. Without this flag,
     * [lines] could not tell that failure apart from a genuine mid-stream
     * break, so a normal disconnect could report itself as a stream failure
     * that never happened. A failure with no stop requested still sets
     * [endedWithError].
     */
    @Volatile
    private var stopRequested: Boolean = false

    /**
     * Set by [forceStop]: the drain deadline has passed. From here on a
     * process [lines] publishes is destroyed at once and never read — F3's
     * guarantee under R46. See [publishLock].
     */
    @Volatile
    private var deadlinePassed: Boolean = false

    /**
     * Guards [process] publication against [forceStop] — F3 (review,
     * 2026-09-22). [spawn] itself runs outside this lock (see [lines]): it can
     * take arbitrarily long (launching a subprocess), and R18 forbids teardown
     * ever waiting on log capture, so the watchdog must never be able to block
     * behind it.
     *
     * What the lock closes is the gap *between* spawn returning and this
     * instance publishing the process it created. F3's original finding: a
     * stop running in that gap saw no process, destroyed nothing, and
     * returned — then [lines] published the process anyway and read it
     * forever, orphaning a subprocess nothing referenced any more
     * (`close during spawn: destroyed=false, linesAfterClose=[still reading
     * after stop]`).
     *
     * **How F3 changed shape under R46.** A *requested* stop that lands during
     * spawn no longer means "destroy whatever spawn produces": that was N1
     * mechanism (a), a short session losing everything. It now means "drain
     * to the sentinel", which is bounded by the deadline like any other
     * drain. The leak guard moved to [deadlinePassed]: once [forceStop] has
     * run, a process spawn publishes afterwards is destroyed at once and
     * never read, so no stop can orphan a subprocess.
     *
     * Every critical section that holds this lock is short and non-blocking —
     * field writes and [Process.destroy], which signals and returns — so
     * holding it is not the wait R18 forbids.
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
    fun lines(): Sequence<String> {
        val proc = spawnAndPublish() ?: return emptySequence()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        return sequence {
            try {
                yieldAll(
                    generateSequence { readOrNull(reader) }
                        .takeWhile { sentinel !in it }
                        .filterNot { SENTINEL_PREFIX in it },
                )
            } finally {
                finish(reader)
            }
        }
    }

    /**
     * Spawns `logcat` and publishes it for [forceStop], or — if the deadline
     * has already passed — destroys it unread and returns null. Also null on
     * a spawn failure.
     */
    private fun spawnAndPublish(): Process? {
        val proc = runCatching { spawn() }.getOrNull()
        val published =
            proc != null &&
                synchronized(publishLock) {
                    // F3: forceStop() either ran before this block (deadlinePassed
                    // is visible here — both sides hold publishLock) or runs after
                    // it and finds the process to destroy. There is no third order.
                    if (!deadlinePassed) process = proc
                    !deadlinePassed
                }
        if (proc != null && !published) {
            // The deadline has passed: nothing else can reach this process,
            // so this branch must be the one that destroys it. Never read —
            // no line from it reaches the ring.
            runCatching { proc.destroy() }
            runCatching { proc.inputStream.close() }
        }
        return proc.takeIf { published }
    }

    /** One read; a failure ends the sequence, and is an error only if no stop was requested (D2). */
    private fun readOrNull(reader: BufferedReader): String? =
        runCatching { reader.readLine() }
            .onFailure { if (!stopRequested) endedWithError = true }
            .getOrNull()

    /**
     * Runs on the capture thread only, once [lines] is done reading. Destroys
     * the subprocess, then closes the reader — D3's order. The order no
     * longer prevents a deadlock (the thread closing the reader is the one
     * that was reading it, so no read can be in flight), but destroying first
     * still stops `logcat` writing into a pipe about to lose its reader.
     */
    private fun finish(reader: BufferedReader) {
        synchronized(publishLock) {
            runCatching { process?.destroy() }
            process = null
        }
        runCatching { reader.close() }
    }

    /**
     * Records that a stop was requested. Does not destroy, close, or wait on
     * anything: the capture drains to [sentinel] on its own thread. Returns
     * immediately (R18).
     */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * The watchdog's call, at the drain deadline. **Only destroys the
     * subprocess — it must never close the reader.**
     *
     * D3 (2026-09-21) is why: `BufferedReader.readLine()` and
     * `BufferedReader.close()` serialize on the same monitor (`java.io.Reader`'s
     * `lock`), and a thread blocked inside a native read holds it for the
     * whole call. The capture thread is very likely sitting in exactly that
     * read when the deadline fires. A `reader.close()` from here would wait
     * for that read to return — which only happens when `logcat` emits
     * another line or dies — and teardown once wedged on precisely that shape
     * (`jstack`: the capture thread `locked` inside `StreamDecoder.readBytes`,
     * another thread `BLOCKED` in `BufferedReader.close()`).
     *
     * [Process.destroy] signals and returns. Killing the subprocess closes the
     * pipe's write end, the blocked `readLine()` drains what is left and then
     * takes EOF, and [lines] closes its own reader on the capture thread.
     *
     * Also arms the F3 guard: a process [spawn] publishes after this has run
     * is destroyed at once and never read.
     */
    fun forceStop() {
        synchronized(publishLock) {
            stopRequested = true
            deadlinePassed = true
            runCatching { process?.destroy() }
        }
    }
}

/**
 * `logcat`'s argument list, exposed separately from [spawnLogcat] so a test
 * can assert on it — `ProcessBuilder.command()` just returns the list back,
 * no process spawned — without needing `logcat` to exist in a JVM test
 * environment.
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
 * session, follows without implying `-d`, and delivered its first line in
 * ~1.9 s against ~3.9 s for `-T 1`.
 *
 * **Not the filter-narrowing ruling R30 refused.** R30 declined to drop
 * *diagnostic* lines a live session produces. The epoch loses no line the
 * session produces — it only declines to re-read history that predates it.
 *
 * Deliberately unfiltered by tag: the whole point is to catch output from
 * libraries whose tags this app does not choose.
 */
internal fun logcatProcessBuilder(sinceEpochMillis: Long): ProcessBuilder =
    ProcessBuilder("logcat", "-v", "threadtime", "-T", logcatEpoch(sinceEpochMillis))
        .redirectErrorStream(true)

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_DIGITS = 3

/** `logcat -T`'s `<seconds>.<millis>` form of an epoch in milliseconds. */
private fun logcatEpoch(epochMillis: Long): String =
    "${epochMillis / MILLIS_PER_SECOND}.${(epochMillis % MILLIS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0')}"

internal fun spawnLogcat(sinceEpochMillis: Long): Process = logcatProcessBuilder(sinceEpochMillis).start()
