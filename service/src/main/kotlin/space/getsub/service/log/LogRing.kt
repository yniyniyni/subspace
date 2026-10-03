// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * A bounded, rotating on-disk line ring.
 *
 * M8.5 spec §3.3: the ring is on disk rather than in memory because W7's entire
 * diagnostic plan depends on reading the log *after* an `am force-stop`, which
 * takes any in-memory buffer with it.
 *
 * Two files, newest-wins: [current] is appended to until it exceeds
 * [maxBytesPerFile], at which point it becomes [previous] and a fresh [current]
 * starts. So the ring holds between one and two files' worth of lines, and the
 * line lost to rotation is always the oldest one.
 *
 * **Rotation failure fallback:** [File.renameTo] and [File.delete] return false on
 * failure rather than throwing. If rotation fails (e.g. because [previous] cannot be
 * replaced), [current] is cleared instead of growing unbounded. This preserves the
 * bounded guarantee and keeps the newest line: what is lost is older history, which
 * was about to be discarded anyway. Logging must never abort a teardown, and bounded
 * beats keeping the most history.
 *
 * **`log.0` stays open.** Opening, writing and closing it for every line, after
 * three `stat`s, was a quarter of the capture thread's CPU under a connection
 * storm (row 7, Pass 4, `docs/agent/research/2026-09-26-m8.5-row7-release.md`).
 * [append] is one unbuffered `write`; [appendAll], which the capture uses, writes a
 * whole batch in one, so a killed process or an `IOException` can lose up to one batch
 * (the capture flushes at most every 100 ms, and at most 256 lines at a time).
 * The size is counted in memory and re-read from the open
 * file before any rotation, because `LogRepository.clear` in `:main` empties
 * `log.0` in place rather than deleting it (a deleted file would leave this
 * writer on an unlinked inode). There is one ring per directory per process
 * ([shared]), so only one writer ever holds the file.
 *
 * Every operation swallows [java.io.IOException]. Logging is a diagnostic, and
 * a diagnostic that can abort a tunnel teardown is worse than one that silently
 * misses a line — ARCHITECTURE.md §5.4 requires teardown to finish anyway.
 *
 * **Write-only from here.** [append] is the only production caller of this
 * class (`:bg`'s [space.getsub.service.log.LogCapture]); the viewer reads the
 * same two files back through `:core:data`'s `LogRepository`, a separate
 * implementation rather than a call into this one — `:core:data` cannot
 * depend on `:service` (M8.5 spec §3.4). `LogRepository`'s own KDoc names the
 * duplication; keep the two in step if [current]/[previous]'s filenames ever
 * change. A prior revision carried `readAll`/`clear`/`totalBytes` accessors
 * here for tests to use as a window into that same state — genuinely dead in
 * production (review finding M2) — which `LogRingTest` now reads back
 * through the files directly instead.
 */
internal class LogRing(
    private val dir: File,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
) : LineSink {
    private val current get() = File(dir, "log.0")
    private val previous get() = File(dir, "log.1")

    private val lock = Any()

    /** `log.0`, held open in append mode. Null until the first append, or after a failure. */
    private var stream: FileOutputStream? = null

    /** Bytes in `log.0` as this ring last knew them; re-read before any rotation. */
    private var size = 0L

    private var appendsSinceExistenceCheck = 0

    override fun append(line: String) = appendAll(listOf(line))

    /**
     * Writes [lines] with one `write` per file they land in, rotating exactly where
     * line-by-line appends would have: before any line that finds `log.0` full.
     */
    override fun appendAll(lines: List<String>) {
        synchronized(lock) {
            runCatching { writeBatch(lines) }.onFailure { closeStream() }
        }
    }

    private fun writeBatch(lines: List<String>) {
        // The existence check counts lines, not stream opens, so a batch of many lines
        // reaches it as soon as that many single appends would have.
        appendsSinceExistenceCheck += lines.size
        openStream() // sets [size] from the file on first use, before the first check
        val pending = ByteArrayOutputStream()
        for (line in lines) {
            if (size + pending.size() >= maxBytesPerFile) {
                flushPending(pending)
                rotateIfFull()
            }
            pending.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
        flushPending(pending)
    }

    private fun flushPending(pending: ByteArrayOutputStream) {
        if (pending.size() == 0) return
        openStream().write(pending.toByteArray())
        size += pending.size()
        pending.reset()
    }

    private fun rotateIfFull() {
        // `LogRepository.clear` may have emptied the file from `:main`; only the
        // real size decides a rotation.
        size = openStream().channel.size()
        if (size >= maxBytesPerFile) {
            rotate()
            openStream()
        }
    }

    /**
     * The open `log.0`, opening it if needed. Every [EXISTENCE_CHECK_INTERVAL]
     * appends it checks that `log.0` still exists, so a file deleted from outside
     * costs at most that many lines instead of everything until the next rotation.
     */
    private fun openStream(): FileOutputStream {
        val open = stream
        if (open != null && stillCurrent()) return open
        closeStream()
        if (!dir.exists()) dir.mkdirs()
        val opened = FileOutputStream(current, true)
        stream = opened
        size = opened.channel.size()
        return opened
    }

    /** True between checks; once [EXISTENCE_CHECK_INTERVAL] lines have gone by, whether `log.0` exists. */
    private fun stillCurrent(): Boolean {
        if (appendsSinceExistenceCheck < EXISTENCE_CHECK_INTERVAL) return true
        appendsSinceExistenceCheck = 0
        return current.exists()
    }

    private fun rotate() {
        closeStream()
        if (previous.exists()) previous.delete()
        if (!current.renameTo(previous)) {
            current.delete()
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }

    internal companion object {
        const val DEFAULT_MAX_BYTES_PER_FILE: Long = 512L * 1024

        /** Appends between checks that `log.0` has not been deleted from under the open stream. */
        const val EXISTENCE_CHECK_INTERVAL = 256

        private val rings = mutableMapOf<String, LogRing>()

        /**
         * The one ring for [dir] in this process. Each `TunnelService` instance used to
         * build its own, and a capture from a destroyed instance can still be draining
         * when the next one starts. Two rings holding `log.0` open would each count its
         * size and rotate it, leaving the other writing into `log.1`.
         */
        fun shared(dir: File): LogRing =
            synchronized(rings) { rings.getOrPut(dir.absolutePath) { LogRing(dir) } }
    }
}
