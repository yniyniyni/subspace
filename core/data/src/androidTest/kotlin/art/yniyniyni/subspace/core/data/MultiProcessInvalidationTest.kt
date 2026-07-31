// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import art.yniyniyni.subspace.core.data.db.ProfileEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.data.di.subspaceDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test

// Real milliseconds, not virtual time. runTest's scheduler cannot drive any of
// this: Room re-queries a Flow on its own query executor, and the second
// process runs on the platform's schedule, neither of which a TestDispatcher
// advances. See ProfileRepositoryTest for the same lesson learned the hard way.
private const val BARRIER_TIMEOUT_MS = 10_000L
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val INVALIDATION_TIMEOUT_MS = 20_000L

/**
 * Proves that a write performed by a *different process* invalidates this process's cached query.
 *
 * ARCHITECTURE.md §3 names this as a silent failure: `:main` and `:bg` each hold their own
 * `SubspaceDatabase` over one file, and without `enableMultiInstanceInvalidation()` a write in one
 * never wakes the other's observers. No exception, no log — the UI just serves stale rows forever.
 * The only way to catch that is to actually run two processes, so this test does.
 *
 * Three properties make it a real guard rather than a green tick:
 *
 * 1. **A file-backed database.** `inMemoryDatabaseBuilder` is per-process by construction; a second
 *    process opening the same in-memory "name" gets a different, empty database, so the test would
 *    be meaningless. (`ProfileRepositoryTest.observeGroupsReEmitsAfterAnImport` is in-memory and
 *    single-process, and says nothing whatsoever about this.)
 * 2. **A real second process.** [WriterService] is declared `android:process=":writer"` in the
 *    androidTest manifest, and the test asserts on the PID and process name that process recorded
 *    for itself. A same-process write would re-emit with or without the flag.
 * 3. **A barrier, then a distinct later emission.** The pre-write emission is received and asserted
 *    empty *before* the writer is bound, so the row cannot already have been present when the
 *    collector first queried. `Flow.first { it.isNotEmpty() }` would be satisfied by that very first
 *    query if the write happened to land earlier, and would pass with invalidation entirely broken.
 *
 * Both this process's database and the writer's are built by
 * [art.yniyniyni.subspace.core.data.di.subspaceDatabase], the single production call site of the
 * flag. Deleting that line from `DataModule.kt` makes this test fail — which was verified by doing
 * exactly that, not by reasoning about it.
 *
 * Backtick names with spaces are avoided here for the same reason the other instrumented tests
 * avoid them: at `minSdk 26` D8 rejects spaces in the synthetic class names coroutine builders
 * derive from the enclosing method's JVM name.
 */
class MultiProcessInvalidationTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var db: SubspaceDatabase
    private var connection: ServiceConnection? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        deleteTestDatabases()
        // A fresh filename per run, rather than one fixed name. A `:writer`
        // process left cached by an earlier run still holds an open handle on
        // the file this test deletes in @Before; on Linux that handle keeps the
        // unlinked inode alive, so that process would write into a database
        // this one can no longer see, and the test would fail for a reason that
        // looks identical to a missing invalidation flag. A new name makes each
        // run hermetic no matter what is still resident.
        databaseName = "$TEST_DB_PREFIX-${System.currentTimeMillis()}.db"
        db = subspaceDatabase(context, databaseName)
    }

    @After
    fun tearDown() {
        connection?.let { context.unbindService(it) }
        connection = null
        db.close()
        deleteTestDatabases()
    }

    @Test
    fun aWriteFromASecondProcessInvalidatesThisProcessesQuery() {
        runBlocking {
            val emissions = Channel<List<ProfileEntity>>(Channel.UNLIMITED)
            val collector =
                launch(Dispatchers.IO) {
                    db.profileDao().observeProfiles().collect { emissions.send(it) }
                }

            try {
                // Barrier: the collector's own initial query, received before
                // the writer process is even bound. Room registers a Flow's
                // invalidation observer before running that first query, so
                // once this returns the collector is provably watching.
                withTimeout(BARRIER_TIMEOUT_MS) { emissions.receive() } shouldBe emptyList()

                val connected = bindWriter()
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                    ?: throw AssertionError(
                        "WriterService never connected within $CONNECT_TIMEOUT_MS ms. The :writer " +
                            "process did not start, so this run proves nothing about invalidation.",
                    )

                // Require a further, distinct emission carrying the row. Skip
                // any interim empty re-signal, but never accept the barrier
                // emission itself as the answer.
                val afterWrite =
                    withTimeoutOrNull(INVALIDATION_TIMEOUT_MS) {
                        var seen = emissions.receive()
                        while (seen.isEmpty()) {
                            seen = emissions.receive()
                        }
                        seen
                    } ?: throw staleReadDiagnosis()

                afterWrite.single().name shouldBe WRITTEN_PROFILE_NAME
                assertTheWriteCameFromAnotherProcess()
            } finally {
                collector.cancel()
            }
        }
    }

    /**
     * Asserts the row was written by a different OS process, from the identity the writer recorded.
     *
     * Without this the test could be satisfied by a same-process write, which re-emits with or
     * without `enableMultiInstanceInvalidation()` — a green tick that guards nothing. Comparing PIDs
     * is the evidence; the process-name checks say *which* process, so a misconfigured
     * `android:process` shows up as a named failure rather than as a mysterious pass.
     */
    private suspend fun assertTheWriteCameFromAnotherProcess() {
        val writerPid = setting(WRITER_PID_KEY)?.toIntOrNull()
        val writerProcess = setting(WRITER_PROCESS_KEY)
        if (writerPid == null || writerProcess == null) {
            throw AssertionError("The writer recorded no identity; it may not have run at all.")
        }
        if (writerPid == Process.myPid()) {
            throw AssertionError(
                "The row was written by PID $writerPid, which is this test process. " +
                    "android:process=\":writer\" is not taking effect, so this test would pass " +
                    "with cross-process invalidation switched off.",
            )
        }
        writerProcess shouldEndWith ":writer"
        (writerProcess == currentProcessName()) shouldBe false
    }

    /** Binds [WriterService], returning a signal that completes once its process is up. */
    private fun bindWriter(): CompletableDeferred<Unit> {
        val connected = CompletableDeferred<Unit>()
        val serviceConnection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    connected.complete(Unit)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
        val intent =
            Intent(context, WriterService::class.java)
                .putExtra(WriterService.EXTRA_DATABASE_NAME, databaseName)
        if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
            throw AssertionError(
                "bindService(WriterService) returned false — the service is not declared in " +
                    "core/data/src/androidTest/AndroidManifest.xml.",
            )
        }
        connection = serviceConnection
        return connected
    }

    /**
     * The failure message for the case this whole test exists to catch.
     *
     * A direct `profileCount()` is a fresh SQL statement, not a cached observer result, so it sees
     * what is genuinely on disk. Rows on disk plus no re-emission is precisely §3's silent failure.
     */
    private suspend fun staleReadDiagnosis(): AssertionError {
        val onDisk = db.profileDao().profileCount()
        return AssertionError(
            "observeProfiles() never re-emitted within $INVALIDATION_TIMEOUT_MS ms after the " +
                ":writer process inserted its row. A direct query from this process now sees " +
                "$onDisk row(s) on disk (writer pid=${setting(WRITER_PID_KEY)}, " +
                "process=${setting(WRITER_PROCESS_KEY)}). Rows present but no emission is exactly " +
                "the ARCHITECTURE.md §3 failure: cross-process invalidation is off, so this " +
                "process would serve stale rows forever. Check that subspaceDatabase() in " +
                "DataModule.kt still calls enableMultiInstanceInvalidation().",
        )
    }

    private suspend fun setting(key: String): String? = db.settingDao().observe(key).first()

    private fun deleteTestDatabases() {
        context.databaseList().filter { it.startsWith(TEST_DB_PREFIX) }.forEach(context::deleteDatabase)
    }
}
