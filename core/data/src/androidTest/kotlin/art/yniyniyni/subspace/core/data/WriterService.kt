// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.data

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import art.yniyniyni.subspace.core.data.db.ProfileEntity
import art.yniyniyni.subspace.core.data.db.ProfileGroupEntity
import art.yniyniyni.subspace.core.data.db.SettingEntity
import art.yniyniyni.subspace.core.data.db.SubspaceDatabase
import art.yniyniyni.subspace.core.data.di.subspaceDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

/** Prefix for every database file this test creates, so leftovers are identifiable and deletable. */
internal const val TEST_DB_PREFIX = "invalidation-test"

/** The row the `:writer` process inserts, and the string the reader must eventually see. */
internal const val WRITTEN_PROFILE_NAME = "written from :writer"

/** Settings key holding the writer's own PID — the test's proof the write came from elsewhere. */
internal const val WRITER_PID_KEY = "test.writer.pid"

/** Settings key holding the writer's own process name, expected to end in `:writer`. */
internal const val WRITER_PROCESS_KEY = "test.writer.process"

/**
 * The name of the process this code is executing in.
 *
 * `Application.getProcessName()` only exists from API 28 and this module's `minSdk` is 26, so the
 * older path reads `/proc/self/cmdline`, which the platform sets to the process name at fork time.
 */
internal fun currentProcessName(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        // cmdline is NUL-padded; trim every control character and space off both ends.
        File("/proc/self/cmdline").readText().trim { it <= ' ' }
    }

/**
 * Writes one profile row from a genuinely separate process.
 *
 * The separation is the entire point. A same-process write invalidates whether or not
 * `enableMultiInstanceInvalidation()` is set, so a same-process test would pass in both
 * configurations and prove nothing — which is exactly the silent failure ARCHITECTURE.md §3 warns
 * about, reproduced in the test suite. The `android:process=":writer"` attribute in the androidTest
 * manifest is what makes this real; without it the test is theatre.
 *
 * It writes its own PID and process name into `settings` *before* the profile row so that, by the
 * time the reader observes the profile, the evidence of who wrote it is already committed.
 *
 * `runBlocking` is permitted here: §12 forbids it outside tests, and this is test-only source in
 * `androidTest` (`scripts/check-forbidden.sh` excludes that source set for the same reason).
 */
class WriterService : Service() {
    private var database: SubspaceDatabase? = null

    /**
     * Opens the database named in the intent and starts the write on a background thread.
     *
     * Bound rather than started: a background `startService` is refused outright on API 26+, and a
     * binding additionally gives the test an `onServiceConnected` callback — a bounded, observable
     * "the second process came up" signal, so a failure to start reports as a distinct failure
     * rather than as an unexplained invalidation timeout.
     */
    override fun onBind(intent: Intent): IBinder {
        val name = intent.getStringExtra(EXTRA_DATABASE_NAME) ?: DEFAULT_DATABASE_NAME
        val db = subspaceDatabase(applicationContext, name)
        database = db
        Thread({ write(db) }, "subspace-writer").start()
        return Binder()
    }

    /**
     * Closes the database only once the binding is gone.
     *
     * Deliberately not closed at the end of [write]: Room dispatches the invalidation broadcast
     * asynchronously after the transaction commits, and `close()` tears down the scope that
     * dispatch runs on. Closing immediately would race the very notification this test exists to
     * observe, and would fail intermittently for a reason that looks exactly like a missing flag.
     */
    override fun onDestroy() {
        database?.close()
        database = null
        super.onDestroy()
    }

    private fun write(db: SubspaceDatabase) =
        runBlocking {
            db.settingDao().put(SettingEntity(WRITER_PID_KEY, Process.myPid().toString()))
            db.settingDao().put(SettingEntity(WRITER_PROCESS_KEY, currentProcessName()))

            val groupId =
                db.profileDao().insertGroup(
                    ProfileGroupEntity(name = "writer", source = "MANUAL", position = 0, createdAt = 0L),
                )
            db.profileDao().insertProfile(
                ProfileEntity(
                    groupId = groupId,
                    kind = "TYPED",
                    identityHash = "written",
                    name = WRITTEN_PROFILE_NAME,
                    protocol = "vless",
                    // Documentation range (RFC 5737), never a real endpoint — §5.6.
                    address = "198.51.100.1",
                    port = 443,
                    transport = "tcp · reality",
                    outbound = "{}",
                    rawJson = null,
                    position = 0,
                    lastConnectedAt = null,
                    lastError = null,
                    createdAt = 0L,
                ),
            )
        }

    companion object {
        /** Intent extra naming the database file to open. The test picks a fresh name per run. */
        const val EXTRA_DATABASE_NAME = "art.yniyniyni.subspace.core.data.EXTRA_DATABASE_NAME"

        /** Fallback filename, used only if the extra is somehow absent. */
        const val DEFAULT_DATABASE_NAME = "$TEST_DB_PREFIX.db"
    }
}
