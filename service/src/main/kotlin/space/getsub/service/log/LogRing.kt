// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service.log

import java.io.File

/**
 * A bounded, rotating on-disk line ring.
 *
 * Spec §3.3: the ring is on disk rather than in memory because W7's entire
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
 * Every operation swallows [java.io.IOException]. Logging is a diagnostic, and
 * a diagnostic that can abort a tunnel teardown is worse than one that silently
 * misses a line — ARCHITECTURE.md §5.4 requires teardown to finish anyway.
 */
internal class LogRing(
    private val dir: File,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
) {
    private val current get() = File(dir, "log.0")
    private val previous get() = File(dir, "log.1")

    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                if (current.exists() && current.length() >= maxBytesPerFile) {
                    if (previous.exists()) previous.delete()
                    if (!current.renameTo(previous)) {
                        // Rotation failed (renameTo returns false on failure). Clear
                        // current to prevent unbounded growth. Oldest history is lost,
                        // but bounded is the guarantee that matters.
                        current.delete()
                    }
                }
                current.appendText(line + "\n")
            }
        }
    }

    /** Oldest first, so the caller can render top-to-bottom without reversing. */
    fun readAll(): List<String> =
        synchronized(lock) {
            runCatching {
                buildList {
                    if (previous.exists()) addAll(previous.readLines())
                    if (current.exists()) addAll(current.readLines())
                }
            }.getOrDefault(emptyList())
        }

    fun clear() {
        synchronized(lock) {
            runCatching {
                current.delete()
                previous.delete()
            }
        }
    }

    fun totalBytes(): Long =
        synchronized(lock) {
            runCatching {
                (if (current.exists()) current.length() else 0L) +
                    (if (previous.exists()) previous.length() else 0L)
            }.getOrDefault(0L)
        }

    internal companion object {
        /**
         * Spec §3.3. About an hour of a talkative session, and small enough that
         * a share is sendable over a chat app. A guess in the same sense
         * [space.getsub.service.TrafficSamplerLoop]'s interval is — spec §9 row 8
         * is what settles it.
         */
        const val DEFAULT_MAX_BYTES_PER_FILE: Long = 512L * 1024
    }
}
