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

    /**
     * Blocking, and consumed on a dedicated thread — this follows the
     * subprocess until [close].
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
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        return generateSequence { runCatching { reader.readLine() }.getOrNull() }
    }

    fun close() {
        runCatching { process?.destroy() }
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
