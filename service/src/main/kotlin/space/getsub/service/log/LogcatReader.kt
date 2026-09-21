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
        process = proc
        val newReader = BufferedReader(InputStreamReader(proc.inputStream))
        reader = newReader
        return generateSequence {
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

    fun close() {
        closeRequested = true
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        reader = null
        process = null
    }
}

/**
 * `-v threadtime` for a stable, parseable prefix; no `-d`, so it follows.
 *
 * Deliberately unfiltered by tag: the whole point is to catch output from
 * libraries whose tags this app does not choose.
 */
private fun spawnLogcat(): Process =
    ProcessBuilder("logcat", "-v", "threadtime")
        .redirectErrorStream(true)
        .start()
