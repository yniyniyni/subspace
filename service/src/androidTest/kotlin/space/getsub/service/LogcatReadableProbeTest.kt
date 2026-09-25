// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Spec §3.1: can a non-privileged app read its own UID's logcat on this device?
 *
 * `READ_LOGS` is not grantable to ordinary apps. The documented behaviour is
 * that logd returns only the caller's own UID's entries, which is the whole
 * basis of the capture design. ARCHITECTURE.md §10.5 forbids assuming it.
 */
@RunWith(AndroidJUnit4::class)
class LogcatReadableProbeTest {
    // Brief's method name used backticks with spaces; renamed to camelCase — the
    // service module's minSdk (26) yields a DEX version below 040, which D8
    // rejects space characters in a SimpleName for (confirmed by build failure
    // during this task). Logic is otherwise verbatim from the brief.
    @Test
    fun ourOwnLogLineComesBackOutOfLogcat() {
        val marker = "SUBSPACE-PROBE-${System.nanoTime()}"
        Log.w("SubspaceProbe", marker)
        // Give logd a moment to accept the write before draining.
        Thread.sleep(500)

        val found =
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime")).use { proc ->
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.any { marker in it }
                }
            }

        assertTrue(
            "logcat did not return our own line — spec §3.6 fallback applies",
            found,
        )
    }

    private inline fun <T> Process.use(block: (Process) -> T): T =
        try {
            block(this)
        } finally {
            destroy()
        }
}
