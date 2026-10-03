// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Incremental reader over the log ring `:bg` writes (M8.5 spec §3.4, as amended).
 *
 * Keeps a byte offset into `log.0` and reads only what was appended since. A
 * rotation (`log.0` renamed over `log.1`, fresh `log.0`) or a clear shows up as
 * `log.1`'s (length, mtime) changing or `log.0` shrinking below the offset; either
 * triggers one full re-read. Only complete lines are consumed: a line the writer
 * is mid-way through stays for the next poll, so nothing is shown truncated.
 * Splitting only at `\n` also never splits a UTF-8 sequence.
 *
 * Plain blocking file I/O and not thread-safe: `LogRepository.tail` drives it
 * from one coroutine on `Dispatchers.IO`.
 */
internal class LogRingTail(
    private val dir: File,
) {
    private data class Stamp(val length: Long, val modified: Long)

    private var initialised = false
    private var log0Offset = 0L
    private var log1Stamp: Stamp? = null
    private var view: List<String> = emptyList()

    // Each early return is one distinct poll outcome: full re-read, no change, or an
    // incremental append.
    @Suppress("ReturnCount")
    fun poll(): List<String>? {
        val log0 = File(dir, LOG0)
        val log1 = File(dir, LOG1)
        val stamp1 = stampOf(log1)
        val length0 = if (log0.isFile) log0.length() else 0L

        if (!initialised || stamp1 != log1Stamp || length0 < log0Offset) {
            initialised = true
            log1Stamp = stamp1
            val (newer, consumed) = readCompleteLines(log0, from = 0L)
            log0Offset = consumed
            view = readCompleteLines(log1, from = 0L).first + newer
            return view
        }
        if (length0 == log0Offset) return null
        val (appended, consumed) = readCompleteLines(log0, from = log0Offset)
        if (appended.isEmpty()) return null
        log0Offset = consumed
        view = view + appended
        return view
    }

    private fun stampOf(file: File): Stamp? = if (file.isFile) Stamp(file.length(), file.lastModified()) else null

    /** @return complete lines from [from] to the last `\n`, and the offset just past it. */
    private fun readCompleteLines(
        file: File,
        from: Long,
    ): Pair<List<String>, Long> {
        if (!file.isFile) return emptyList<String>() to 0L
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length <= from) return@use emptyList<String>() to from
                val bytes = ByteArray((length - from).toInt())
                raf.seek(from)
                raf.readFully(bytes)
                val lastNewline = bytes.lastIndexOf('\n'.code.toByte())
                if (lastNewline < 0) return@use emptyList<String>() to from
                val text = String(bytes, 0, lastNewline, Charsets.UTF_8)
                text.split('\n') to from + lastNewline + 1
            }
        }.getOrElse { emptyList<String>() to from }
    }

    private companion object {
        const val LOG0 = "log.0"
        const val LOG1 = "log.1"
    }
}
