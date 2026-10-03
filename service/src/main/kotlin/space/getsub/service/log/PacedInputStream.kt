// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import java.io.FilterInputStream
import java.io.InputStream

/**
 * Reads [input] no more often than once per [intervalMillis], running [onIdle] just
 * before each underlying read.
 *
 * Row 7 (`docs/agent/research/2026-09-26-m8.5-row7-release.md`, Pass 5): `logcat`
 * writes one line per pipe write, so a reader that keeps up pays a `read` system
 * call, a charset decode and an `available` probe for every line, about a quarter of
 * the capture thread under a connection storm. Waiting out the interval lets the pipe
 * fill, so one read takes the whole batch. Lines wait in the pipe at most
 * [intervalMillis] longer than before, and a process killed in that window loses them,
 * just as it loses whatever is in the pipe today.
 *
 * [onIdle] runs when the reader has used up everything it had, which is where the
 * capture flushes its batch of redacted lines to the ring.
 */
internal class PacedInputStream(
    input: InputStream,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val onIdle: () -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : FilterInputStream(input) {
    private var lastReadAt: Long? = null

    override fun read(): Int {
        pace()
        return super.read().also { lastReadAt = nowMillis() }
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        pace()
        return super.read(b, off, len).also { lastReadAt = nowMillis() }
    }

    /**
     * Always 0. `InputStreamReader` probes `available()` after every chunk to decide
     * whether to read again; reporting nothing makes it return what it has instead of
     * paying an `ioctl` per line.
     */
    override fun available(): Int = 0

    private fun pace() {
        onIdle()
        val last = lastReadAt ?: return
        val wait = last + intervalMillis - nowMillis()
        if (wait > 0) sleep(wait)
    }

    internal companion object {
        /** About ten reads a second; the live viewer itself polls once a second. */
        const val DEFAULT_INTERVAL_MILLIS = 100L
    }
}
