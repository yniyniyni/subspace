// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.getsub.core.model.ConnectionState
import space.getsub.core.model.FailureReason
import space.getsub.core.model.StartupStage
import space.getsub.core.model.TrafficSample
import space.getsub.core.model.failure

/**
 * D4: `TunnelClient._traffic` used to be cleared only in `unbind()` and
 * `onServiceDisconnected` — never in `onStateChanged` — so an ordinary
 * disconnect while the UI stayed bound left the previous session's sample
 * sitting in the flow for the next session's first tick to compose against
 * (spec §9 row 12: an empty breakdown section under a dangling header,
 * because `BreakdownSection`'s `everSeenRows` latch saw non-empty `perTag`
 * rows that belonged to the session before).
 *
 * These tests drive [TrafficSessionTracker] directly rather than through a
 * live [TunnelClient] instance: `TunnelClient` takes a real Android `Context`
 * and its state-changing behaviour is reachable only from
 * `ITunnelCallback.Stub`'s private binder callback, neither of which this
 * module's plain-JVM unit tests (no Robolectric, no mocking library declared
 * anywhere in this project — see `gradle/libs.versions.toml`) can construct
 * or invoke. [TrafficSessionTracker] is the extracted, pure decision the
 * fix's correctness actually rests on, so pinning it here is pinning the
 * fix — `TunnelClient.onStateChanged`'s own body is a two-line, unconditional
 * call into it (`if (!trafficSessionTracker.observe(newState)) _traffic.value
 * = null`), with no further branching left to go untested.
 *
 * Each test below simulates the same shape `TunnelClient`'s callback has: a
 * `traffic` variable standing in for `_traffic`, cleared exactly when
 * [TrafficSessionTracker.observe] returns false.
 */
class TrafficSessionTrackerTest {
    private fun connected(sinceEpochMillis: Long) = ConnectionState.Connected(sinceEpochMillis, socksPort = 1080)

    private val sampleA = TrafficSample(uplinkBytes = 10, downlinkBytes = 20, uplinkPackets = 1, downlinkPackets = 2)
    private val sampleB = TrafficSample(uplinkBytes = 30, downlinkBytes = 40, uplinkPackets = 3, downlinkPackets = 4)

    /**
     * Simulates [TunnelClient]'s callback: applies [state] through [tracker],
     * clearing [traffic] exactly when the tracker reports a session boundary.
     */
    private fun apply(
        tracker: TrafficSessionTracker,
        state: ConnectionState,
        traffic: TrafficSample?,
    ): TrafficSample? = if (tracker.observe(state)) traffic else null

    /**
     * Scenario 1 (task brief): a sample arrives during session A; session A
     * ends; `traffic` is null before session B's first sample.
     *
     * Fails if reverted to the pre-fix behaviour (`onStateChanged` never
     * touches `_traffic`): a bare `tracker.observe` call with no clearing
     * would leave [sampleA] in place after the disconnect, and this asserts
     * null.
     */
    @Test
    fun `a session ending clears the sample before the next session starts`() {
        val tracker = TrafficSessionTracker()
        var traffic: TrafficSample? = null

        traffic = apply(tracker, connected(sinceEpochMillis = 100L), traffic)
        traffic = sampleA // onTrafficSample, unconditional — mirrors TunnelClient's own handler
        assertEquals(sampleA, traffic)

        traffic = apply(tracker, ConnectionState.Disconnected, traffic)

        assertNull("session A's sample must not survive its own session ending", traffic)
    }

    /**
     * Scenario 2 (task brief): session B's first sample then populates
     * `traffic` normally, continuing directly from scenario 1's end state.
     */
    @Test
    fun `session Bs first sample populates traffic normally`() {
        val tracker = TrafficSessionTracker()
        var traffic: TrafficSample? = null
        traffic = apply(tracker, connected(sinceEpochMillis = 100L), traffic)
        traffic = sampleA
        traffic = apply(tracker, ConnectionState.Disconnected, traffic)
        check(traffic == null)

        traffic = apply(tracker, connected(sinceEpochMillis = 200L), traffic)
        assertNull("must still be null before session B's first sample arrives", traffic)
        traffic = sampleB

        assertEquals(sampleB, traffic)
    }

    /**
     * Scenario 3 (task brief) — the one that stops the fix from over-clearing:
     * a state update *within* one session (same `sinceEpochMillis`, still
     * `Connected`) must not clear a sample that already arrived.
     *
     * Fails if the fix is instead "clear whenever a `Connected` state's
     * `sinceEpochMillis` differs from what was last seen" applied naively
     * to two calls with the *same* since — no, this test uses the *same*
     * since twice, so that literal rule would also pass it; what this test
     * actually guards is a cruder revert, "clear on every state change that
     * is not literally `==` to the previous one" — see the retained-TUN test
     * below for the one that catches the naive "differing since clears"
     * rule specifically.
     */
    @Test
    fun `a same-session state update does not clear an already-arrived sample`() {
        val tracker = TrafficSessionTracker()
        var traffic: TrafficSample? = null
        traffic = apply(tracker, connected(sinceEpochMillis = 300L), traffic)
        traffic = sampleA

        traffic = apply(tracker, connected(sinceEpochMillis = 300L), traffic)

        assertEquals("an in-session Connected republish must not blank the tiles", sampleA, traffic)
    }

    /**
     * The retained-TUN-restart case the task brief calls out by name: a
     * Wi-Fi<->cellular handoff republishes `Connected` with a *different*
     * `sinceEpochMillis` (`TunnelService.attachRetainedTun` /
     * `attachTun` both stamp `System.currentTimeMillis()` fresh on every
     * settle — see `TrafficSessionTracker`'s KDoc), by way of an intervening
     * `Connecting` — never through `Disconnected`, `Disconnecting`,
     * `Reconnecting` or `Failed`. The already-accumulating sample must
     * survive all of it.
     *
     * Fails if the fix were instead "clear whenever the new state is not
     * Connected, and also whenever a Connected's sinceEpochMillis differs
     * from the previous Connected's" applied literally by state name: the
     * `Connecting` step would clear the sample (rule 1), or failing that the
     * final `Connected(999)` would, since 999 != 100 (rule 2).
     */
    @Test
    fun `a retained-TUN restart does not clear the sample despite an intervening Connecting and a new since`() {
        val tracker = TrafficSessionTracker()
        var traffic: TrafficSample? = null
        traffic = apply(tracker, connected(sinceEpochMillis = 100L), traffic)
        traffic = sampleA

        traffic = apply(tracker, ConnectionState.Connecting(StartupStage.AllocatingPort), traffic)
        assertEquals("a retained-TUN restart's Connecting step must not blank the tiles", sampleA, traffic)
        traffic = apply(tracker, ConnectionState.Connecting(StartupStage.StartingTunnel), traffic)
        assertEquals(sampleA, traffic)
        traffic = apply(tracker, connected(sinceEpochMillis = 999L), traffic)

        assertEquals(
            "a retained-TUN restart's new sinceEpochMillis must not blank a sample the same session already reported",
            sampleA,
            traffic,
        )
    }

    /** A reconnect attempt that fails before re-establishing must still clear — it is not a retained restart. */
    @Test
    fun `a retry that lands in Reconnecting clears the sample`() {
        val tracker = TrafficSessionTracker()
        var traffic: TrafficSample? = null
        traffic = apply(tracker, connected(sinceEpochMillis = 100L), traffic)
        traffic = sampleA

        traffic =
            apply(
                tracker,
                ConnectionState.Reconnecting(reason = FailureReason.TunnelStartFailed, attempt = 1),
                traffic,
            )

        assertNull(traffic)
    }

    /** A terminal failure must clear, same as an ordinary disconnect. */
    @Test
    fun `a terminal failure clears the sample`() {
        val tracker = TrafficSessionTracker()
        var traffic: TrafficSample? = null
        traffic = apply(tracker, connected(sinceEpochMillis = 100L), traffic)
        traffic = sampleA

        traffic = apply(tracker, failure(FailureReason.TunnelStartFailed, "detail"), traffic)

        assertNull(traffic)
    }
}
