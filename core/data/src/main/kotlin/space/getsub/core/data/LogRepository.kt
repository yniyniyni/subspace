// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Reads the session log ring `:bg` writes.
 *
 * `:feature:settings` reaches the log through this rather than through
 * `:service`'s own file layout, because ARCHITECTURE.md §4 routes every data
 * source through a repository. `:main` and `:bg` are one app with one
 * `filesDir`, so this is an ordinary file read and needs no IPC.
 *
 * **The directory name is duplicated**, not shared: `:core:data` cannot depend
 * on `:service`. It must stay in step with `TunnelService.LOG_DIR_NAME`.
 * Spec §3.4.
 *
 * Every line here was redacted at capture (spec §3.2), so nothing this class
 * returns needs redacting again — and nothing it returns may be assumed to
 * contain a secret that has to be withheld from a share.
 */
public class LogRepository(
    private val dir: File,
) {
    @Inject
    public constructor(
        @ApplicationContext context: Context,
    ) : this(File(context.filesDir, LOG_DIR_NAME))

    /**
     * Oldest first. Reads on IO — a full ring is up to 1 MiB (spec §3.3).
     *
     * `log.1` and `log.0` are each read inside their own [runCatching], not one
     * shared around both: a single shared block would let a failure reading
     * `log.1` discard `log.0` as collateral damage, and `log.0` holds the
     * newest lines — the ones a failure actually shows up in. Losing them to an
     * unrelated problem reading the older file would defeat the point of
     * keeping the ring at all.
     */
    public suspend fun lines(): List<String> =
        withContext(Dispatchers.IO) {
            readRingFile(File(dir, "log.1")) + readRingFile(File(dir, "log.0"))
        }

    public suspend fun clear() {
        withContext(Dispatchers.IO) {
            runCatching {
                File(dir, "log.0").delete()
                File(dir, "log.1").delete()
            }
        }
    }

    private fun readRingFile(file: File): List<String> =
        runCatching { file.takeIf { it.exists() }?.readLines() }.getOrNull().orEmpty()

    private companion object {
        const val LOG_DIR_NAME = "logs"
    }
}
