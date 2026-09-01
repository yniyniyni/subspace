// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How far one rule set's in-flight generation has got.
 *
 * @property totalBytes null when the server declared no `Content-Length`. The
 *   row renders that as an indeterminate bar; rendering it as `0` would read as
 *   a finished download of an empty file.
 */
public data class GeoDownloadProgress(
    public val fileName: String,
    public val downloadedBytes: Long,
    public val totalBytes: Long?,
)

/**
 * The live byte counts behind the routing list's `Downloading 12 MB / 23 MB`
 * row, and the handle its cancel affordance pulls.
 *
 * Deliberately **not** persisted. Progress describes a coroutine that exists
 * right now; a value that survived process death would describe a download that
 * does not, and the row would show a bar that can never advance. What does
 * survive is [RuleSetAssetState] in Room — a killed import is reconciled back to
 * its previous generation, and the row reads `Failed` from the database like any
 * other unfinished generation.
 *
 * §5.6: a [GeoDownloadProgress] carries a filename and two counts. Never a URL.
 */
@Singleton
public class GeoDownloadProgressRegistry
@Inject
public constructor() {
    private val _progress = MutableStateFlow<Map<Long, GeoDownloadProgress>>(emptyMap())

    /** Rule set id to its in-flight generation's progress. Absent means nothing is downloading. */
    public val progress: StateFlow<Map<Long, GeoDownloadProgress>> = _progress.asStateFlow()

    /**
     * The [Job] materialising each rule set's generation.
     *
     * A plain map guarded by nothing would be wrong here: [report] runs on the
     * download's IO thread while [cancel] runs on the main thread, and the two
     * race by construction.
     */
    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * Runs [block] as [setId]'s cancellable materialisation.
     *
     * The job is read from the calling coroutine rather than launched here, so
     * cancellation stops the real import — a job this registry owned separately
     * could only stop a proxy for it.
     */
    internal suspend fun <T> track(
        setId: Long,
        block: suspend () -> T,
    ): T {
        val job = currentCoroutineContext()[Job]
        if (job != null) jobs[setId] = job
        try {
            return block()
        } finally {
            // Remove only our own registration: a retry that started while this
            // one was unwinding owns the slot now, and clearing it blindly would
            // leave the new download uncancellable.
            jobs.remove(setId, job)
            clear(setId)
        }
    }

    /** Records [setId]'s running byte count. Called from the download thread. */
    internal fun report(
        setId: Long,
        fileName: String,
        downloadedBytes: Long,
        totalBytes: Long?,
    ) {
        _progress.update { it + (setId to GeoDownloadProgress(fileName, downloadedBytes, totalBytes)) }
    }

    /** Drops [setId]'s bar. Idempotent — a generation can end in several ways. */
    internal fun clear(setId: Long) {
        _progress.update { it - setId }
    }

    /**
     * Stops [setId]'s materialisation. A no-op when nothing is in flight.
     *
     * The importer treats the resulting [kotlinx.coroutines.CancellationException]
     * as a cancelled generation: the previous generation stays live and its files
     * stay on disk (spec §7.4).
     */
    public fun cancel(setId: Long) {
        jobs[setId]?.cancel()
    }
}
