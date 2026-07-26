// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import org.junit.Assert.assertFalse
import org.junit.Test

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
}
