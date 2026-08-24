// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.os.ParcelFileDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Exercises the hev-socks5-tunnel JNI bridge on a real device.
 *
 * A successful native build proves nothing here: a mismatch between the C
 * function names and the Kotlin `external` declarations produces
 * `UnsatisfiedLinkError` at *call* time, and the concurrency hazards below are
 * invisible to the compiler entirely.
 *
 * Config-string assertions deliberately live in `src/test` instead — they need
 * no device, and a test that only runs on attached hardware is a test CI skips.
 *
 * What is NOT tested here: actually tunnelling packets. That needs a live TUN fd
 * from `VpnService.Builder`, which needs user consent, so it belongs to the
 * manual on-device checklist in ARCHITECTURE.md §11 — as §10.1 insists.
 */
class Tun2SocksTest {
    private fun config() = tun2socksConfig(socksPort = 10808, mtu = 8500)

    private fun pipe(): Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()

    private fun Array<ParcelFileDescriptor>.closeAll() = forEach(ParcelFileDescriptor::close)

    @Test
    fun isRunningBindsAndReportsIdleOnAFreshProcess() {
        // Touching the object runs System.loadLibrary; the call proves
        // nativeIsRunning resolves.
        assertFalse("expected no tunnel running on a fresh process", Tun2Socks.isRunning)
    }

    @Test
    fun stopBindsAndIsSafeWhenNothingIsRunning() {
        // Proves nativeStop resolves, and covers §5.4's easy half: teardown can
        // fire when the tunnel is already down and must not crash :bg.
        Tun2Socks.stop()
        assertFalse(Tun2Socks.isRunning)
    }

    @Test
    fun startBindsAndRefusesANegativeFileDescriptor() {
        // Proves nativeStart resolves, and guards a real crash: the first version
        // of this bridge had no fd check and this call took the whole process
        // down — hev-socks5-tunnel aborts on an invalid descriptor rather than
        // returning an error. If the guard is removed this test dies as
        // "Process crashed", not as an ordinary assertion failure.
        val started = Tun2Socks.start(config(), tunFd = -1)

        assertFalse("start must refuse a negative fd", started)
        assertFalse("tunnel must not be running after a refused start", Tun2Socks.isRunning)
    }

    @Test
    fun concurrentStopsDoNotHangOrDoubleJoin() {
        // Regression test for a Critical found in review. §5.4's three teardown
        // paths — disconnect, onRevoke, onDestroy — are not serialised. An
        // earlier revision dropped the mutex around quit()+join, so two stops
        // could both call hev_socks5_tunnel_quit(); the second busy-waits forever
        // on an event fd the first already closed, and both join the same tid.
        //
        // If that regresses, this test hangs rather than failing — which is
        // itself the signal, since a wedged teardown is the §5.4 failure mode.
        val threads =
            (1..4).map {
                Thread {
                    repeat(5) {
                        Tun2Socks.stop()
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        threads.forEach { assertFalse("a teardown thread is still stuck", it.isAlive) }
        assertFalse(Tun2Socks.isRunning)
    }

    @Test
    fun repeatedRefusedStartsDoNotLeaveState() {
        // A user retrying a bad server hits start repeatedly. Each refused start
        // must leave the bridge idle and reapable, not accumulate workers.
        repeat(10) {
            assertFalse(Tun2Socks.start(config(), tunFd = -1))
        }
        Tun2Socks.stop()
        assertFalse(Tun2Socks.isRunning)
    }

    @Test
    fun stopBeforeNativeReadinessRequestsQuitWithoutWaitingForTheEventFd() {
        val pipe = pipe()
        val stopFailure = AtomicReference<Throwable?>()
        var stopThread: Thread? = null

        Tun2SocksNativeTestHook.armPause()
        try {
            assertTrue(Tun2Socks.start(config(), pipe[0].fd))
            assertTrue(
                "native worker did not reach the pre-initialization hook",
                Tun2SocksNativeTestHook.awaitPaused(10_000),
            )

            stopThread =
                Thread {
                    runCatching(Tun2Socks::stop).exceptionOrNull()?.let(stopFailure::set)
                }.apply { start() }

            val quitReturnedBeforeReadiness =
                Tun2SocksNativeTestHook.awaitQuitReturned(5_000)

            // Always release and reap, including on the old busy-waiting code,
            // so a RED run cannot strand the instrumentation process.
            Tun2SocksNativeTestHook.release()
            stopThread.join(10_000)

            assertTrue(
                "quit waited for event initialization instead of recording a pending stop",
                quitReturnedBeforeReadiness,
            )
            assertFalse("stop did not join the worker after release", stopThread.isAlive)
            assertNull(stopFailure.get())
            assertFalse(Tun2Socks.isRunning)
        } finally {
            Tun2SocksNativeTestHook.release()
            stopThread?.join(10_000)
            pipe.closeAll()
        }
    }

    @Test
    fun oneHundredImmediateStopsLeaveTheBridgeReusable() {
        repeat(100) { iteration ->
            val pipe = pipe()
            try {
                assertTrue("start $iteration failed", Tun2Socks.start(config(), pipe[0].fd))
                Tun2Socks.stop()
                assertFalse("iteration $iteration remained running", Tun2Socks.isRunning)
            } finally {
                pipe.closeAll()
            }
        }

        assertSubsequentStartSucceeds()
    }

    @Test
    fun oneHundredConcurrentDuplicateStopsJoinExactlyOnce() {
        repeat(100) { iteration ->
            val pipe = pipe()
            try {
                assertTrue("start $iteration failed", Tun2Socks.start(config(), pipe[0].fd))
                val ready = CountDownLatch(4)
                val go = CountDownLatch(1)
                val failures = AtomicReference<Throwable?>()
                val threads =
                    (1..4).map {
                        Thread {
                            ready.countDown()
                            go.await()
                            runCatching(Tun2Socks::stop).exceptionOrNull()?.let(failures::set)
                        }.apply { start() }
                    }

                val allThreadsReady = ready.await(10, TimeUnit.SECONDS)
                go.countDown()
                threads.forEach { it.join(10_000) }

                assertTrue("iteration $iteration did not start every stop thread", allThreadsReady)
                assertTrue(
                    "iteration $iteration left a stop thread blocked",
                    threads.none(Thread::isAlive),
                )
                assertNull(failures.get())
                assertFalse("iteration $iteration remained running", Tun2Socks.isRunning)
            } finally {
                pipe.closeAll()
            }
        }

        assertSubsequentStartSucceeds()
    }

    private fun assertSubsequentStartSucceeds() {
        val pipe = pipe()
        try {
            assertTrue("bridge was not reusable after stress", Tun2Socks.start(config(), pipe[0].fd))
            Tun2Socks.stop()
            assertFalse(Tun2Socks.isRunning)
        } finally {
            pipe.closeAll()
        }
    }
}
