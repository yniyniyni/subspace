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
 *
 * **Write-only from here.** [append] is the only production caller of this
 * class (`:bg`'s [space.getsub.service.log.LogCapture]); the viewer reads the
 * same two files back through `:core:data`'s `LogRepository`, a separate
 * implementation rather than a call into this one — `:core:data` cannot
 * depend on `:service` (spec §3.4). `LogRepository`'s own KDoc names the
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

    override fun append(line: String) {
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
