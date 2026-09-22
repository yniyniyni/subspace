// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import java.io.BufferedReader
import java.io.InputStreamReader

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
 * @param spawn a seam for tests. Production uses [spawnLogcat].
 */
internal class LogcatReader(
    private val spawn: () -> Process = ::spawnLogcat,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    var endedWithError: Boolean = false
        private set

    /**
     * Set by [close] before it touches the reader or the process.
     *
     * D2 (device verification, 2026-09-20): [close] runs on the teardown
     * thread while [lines]' `generateSequence` block may have a `readLine()`
     * in flight on the capture thread. `BufferedReader.readLine()` and
     * `BufferedReader.close()` serialize on the same monitor, so [close]
     * either lands in a gap between two reads — and the *next* read then
     * fails against an already-closed stream — or it waits for whatever read
     * is currently in flight to finish first. Either way, once [close] has
     * run, a read failure that follows it is the closed stream behaving
     * exactly as a closed stream should, not evidence the subprocess or pipe
     * broke. The device observed this landing both ways across two otherwise
     * identical clean disconnects — sometimes the trailing read failed,
     * sometimes it didn't — which is what makes it a race and not a
     * deterministic ordering to special-case instead.
     *
     * Without this flag, [lines] could not tell that failure apart from a
     * genuine mid-stream break, so a normal disconnect could report itself as
     * a stream failure that never happened. Set here, before [close] touches
     * the reader or the process, so the write is visible to the capture
     * thread by the time it observes any effect of the close.
     *
     * This does not guarantee the last line written before [close] is
     * captured — that still depends on whether the subprocess's pipe had
     * already delivered it to this reader's buffer before the close tore the
     * stream down, and nothing here waits to find out (teardown must not
     * block on this capture; see R18). It only fixes what [endedWithError]
     * reports about a stop this class itself initiated.
     */
    @Volatile
    private var closeRequested: Boolean = false

    /**
     * Guards [process] and [reader] publication against [close] — F3 (review,
     * 2026-09-22). [spawn] itself runs outside this lock (see [lines]): it can
     * take arbitrarily long (launching a subprocess), and R18 forbids teardown
     * ever waiting on log capture, so [close] must never be able to block
     * behind it.
     *
     * What the lock actually closes is the gap *between* spawn returning and
     * this instance publishing the process/reader it created. Before this
     * fix, [close] running in that gap saw both fields still null, destroyed
     * nothing, and returned — then [lines] published the process and reader
     * anyway and started reading, orphaning a subprocess [close]'s caller
     * ([LogCapture.stop]) had already discarded its only reference to. See
     * the F3 finding: reproduced deterministically with a latch-driven probe
     * through the [spawn] seam, `close during spawn: destroyed=false,
     * linesAfterClose=[still reading after stop]`.
     *
     * Both critical sections below are short and non-blocking (field writes,
     * plus [Process.destroy] and [BufferedReader.close], which D3's KDoc
     * already establishes cannot block each other in this order) — holding
     * this lock across either is not the wait R18 forbids.
     */
    private val publishLock = Any()

    /**
     * Blocking, and consumed on a dedicated thread — this follows the
     * subprocess until [close].
     *
     * Called at most once per instance — the capture thread is its only caller.
     *
     * A spawn failure yields an empty sequence rather than throwing: capture is
     * a diagnostic, and ARCHITECTURE.md §10.4's rule about failing loudly is
     * about the *start sequence*, not about a logger that could otherwise abort
     * one.
     */
    fun lines(): Sequence<String> {
        val proc =
            runCatching { spawn() }.getOrElse {
                return emptySequence()
            }

        val newReader =
            synchronized(publishLock) {
                // F3: if close() already ran — or runs concurrently and wins
                // this race — closeRequested is visible here (both this read
                // and close()'s write happen inside publishLock) before this
                // process is ever published. Nothing else can reach it once
                // this instance discards it below, so this branch must be the
                // one that destroys it; close() cannot, having found no
                // process to act on.
                if (closeRequested) {
                    null
                } else {
                    process = proc
                    BufferedReader(InputStreamReader(proc.inputStream)).also { reader = it }
                }
            }

        return if (newReader == null) {
            // Same order [close] uses below (D3): destroy before releasing
            // the stream. Never consumed — no line from this process reaches
            // the ring.
            runCatching { proc.destroy() }
            runCatching { proc.inputStream.close() }
            emptySequence()
        } else {
            generateSequence {
                runCatching { newReader.readLine() }
                    .onFailure {
                        // A read failure that follows a requested close is the
                        // expected consequence of that close, not a genuine
                        // mid-stream error — see [closeRequested].
                        if (!closeRequested) {
                            endedWithError = true
                        }
                    }
                    .getOrNull()
            }
        }
    }

    /**
     * D3 (this branch, 2026-09-21): destroys the subprocess *before* closing the
     * reader. That order is load-bearing — swapping it back reintroduces a
     * teardown deadlock, so read this before "tidying" the two lines below.
     *
     * `BufferedReader.readLine()` and `BufferedReader.close()` serialize on the
     * same monitor (`java.io.Reader`'s own `lock`), and a thread blocked inside
     * a native read holds that monitor for the whole call. [lines]' capture
     * thread can be sitting in exactly that blocked read — waiting on the
     * `logcat` subprocess for its next line — when [close] runs on the teardown
     * thread. If [close] called `reader.close()` first, it would not be able to
     * enter that synchronized block until the in-flight read returns; and that
     * read only returns when logcat emits another matching line, or the
     * subprocess dies. The call that kills the subprocess would then be the
     * *next* statement — one this thread can no longer reach, because it is
     * stuck waiting to acquire a monitor the blocked reader already holds. That
     * is a real deadlock, not a slow path: on a quiet device, that next
     * matching line can be arbitrarily far away, and teardown never finishes.
     * `jstack` confirmed this shape earlier on this branch — the capture thread
     * `locked` inside `StreamDecoder.readBytes`, another thread `BLOCKED (on
     * object monitor)` in `BufferedReader.close()`.
     *
     * Destroying first avoids the wait entirely: [Process.destroy] signals and
     * returns without blocking. Killing the subprocess closes the pipe's write
     * end, so the blocked `readLine()` takes EOF, returns null, and releases the
     * monitor on its own — at which point `reader.close()` proceeds freely
     * against a reader nothing is blocked inside any more. [lines]'
     * `generateSequence` ends cleanly on that null, so this path never reaches
     * `onFailure`, and [endedWithError] stays false without [closeRequested]
     * even needing to intervene.
     *
     * No data is lost that the old order preserved: closing the reader first
     * discarded its buffer just the same, so there is nothing this order gives
     * up. `runCatching` around each call only guards against a throw — it does
     * not, and cannot, guard against one of these calls blocking, which is why
     * the order itself is the fix, not the `runCatching`.
     *
     * F3: wrapped in [publishLock] so setting [closeRequested] and acting on
     * [process]/[reader] is atomic with [lines]' own publish step — the two
     * can no longer interleave as "close sees nothing to destroy" followed by
     * "lines publishes and reads anyway". This does not reintroduce the D2/D3
     * deadlock: the lock here is a plain, uncontended `Any()` monitor
     * different from `BufferedReader`'s own internal lock, and neither
     * critical section that holds it performs a blocking call — spawning the
     * subprocess happens in [lines] *before* this lock is ever taken.
     */
    fun close() {
        synchronized(publishLock) {
            closeRequested = true
            runCatching { process?.destroy() }
            runCatching { reader?.close() }
            reader = null
            process = null
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
 * **`-T 1`, not absent (review finding I2).** Without a `-T`/`-t`, `logcat`
 * dumps this UID's entire retained buffer before it starts following —
 * confirmed on device: a ring the spec (§3.5) calls a record of *one
 * session* began with `--------- beginning of main` and a pre-session
 * `:main` line. That costs the ring's budget and the capture thread's CPU on
 * history the session never produced, and a reconnect-reconnect-reconnect
 * repro duplicates that history into the ring on every attempt, evicting the
 * session that actually matters. `-T <count>` shows only the most recent
 * `<count>` lines and then follows, without implying `-d` — unlike `-t`,
 * which dumps and exits. `1` is the smallest count that is still a count
 * (there is no `-T 0`), so this replays at most one line of history, never
 * the whole buffer.
 *
 * **Not the filter-narrowing ruling R30 refused.** R30 declined to drop
 * *diagnostic* lines a live session produces, to fix an unproven CPU cost —
 * narrowing `logcat`'s own tag filter would mean permanently losing lines
 * this app's own components emit. `-T 1` loses no line the session
 * produces: it only declines to re-read history that predates the session
 * the ring is scoped to, and that history is either already sitting in the
 * ring from when it was captured the first time, or was evicted by rotation
 * on purpose. Nothing about *this* session's diagnostic content is affected.
 *
 * Deliberately unfiltered by tag: the whole point is to catch output from
 * libraries whose tags this app does not choose.
 */
internal fun logcatProcessBuilder(): ProcessBuilder =
    ProcessBuilder("logcat", "-v", "threadtime", "-T", "1")
        .redirectErrorStream(true)

private fun spawnLogcat(): Process = logcatProcessBuilder().start()
