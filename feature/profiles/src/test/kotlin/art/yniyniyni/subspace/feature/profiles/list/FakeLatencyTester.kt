// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.model.ConnectionState
import art.yniyniyni.subspace.core.model.LatencyResult
import art.yniyniyni.subspace.core.model.PingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [LatencyTester] that records what it was asked to measure and answers with a
 * fixed result, so the Servers screen's wiring can be exercised without `:bg`,
 * libXray, or a bound service.
 *
 * [resultFor] is settable because a tester that only ever succeeds is how a
 * failure-rendering bug survives to a device run — the same lesson
 * `FakeProfileSource.syncResultToReturn` records for sync.
 */
internal class FakeLatencyTester(
    private val autoComplete: Boolean = true,
) : LatencyTester {
    private val _results = MutableStateFlow<Map<Long, LatencyResult>>(emptyMap())
    override val results: StateFlow<Map<Long, LatencyResult>> = _results.asStateFlow()

    private val _testing = MutableStateFlow<Set<Long>>(emptySet())
    override val testing: StateFlow<Set<Long>> = _testing.asStateFlow()

    override val pingOnLaunch: Flow<Boolean> = MutableStateFlow(true)
    override val pingOnLaunchMetered: Flow<Boolean> = MutableStateFlow(false)

    /** Settable so a test can drive the metered gate without a real network. */
    var metered: Boolean = false

    /** Settable so a test can put a launch run behind an in-flight connect. */
    var state: ConnectionState = ConnectionState.Disconnected

    override fun isMetered(): Boolean = metered

    override fun connectionState(): ConnectionState = state

    var resultFor: (Long) -> LatencyResult = { LatencyResult.ok(DEFAULT_DELAY_MS) }

    var testedIds: List<Long> = emptyList()
        private set
    var testCallCount: Int = 0
        private set
    var cancelCallCount: Int = 0
        private set

    private val launchRunClaimed = mutableSetOf<Long>()

    var lastModes: Map<Long, PingMode> = emptyMap()
        private set

    /** Settable so a test can drive the not-bound path, which returns false. */
    var startSucceeds: Boolean = true

    var releasedGroups: Collection<Long> = emptyList()
        private set

    override fun releaseLaunchRun(groupIds: Collection<Long>) {
        releasedGroups = groupIds
        launchRunClaimed.removeAll(groupIds.toSet())
    }

    override suspend fun test(
        profileIds: List<Long>,
        modes: Map<Long, PingMode>,
    ): Boolean {
        if (profileIds.isEmpty()) return false
        // Mirrors BoundLatencyTester: a run supersedes any run in flight, and the
        // superseded one never reaches its own onFinished, so its rows must be
        // released here or they stay marked forever.
        _testing.value = emptySet()
        testCallCount++
        testedIds = profileIds
        lastModes = modes
        if (autoComplete) {
            _results.value = _results.value + profileIds.associateWith { id -> resultFor(id) }
            _testing.value = _testing.value - profileIds.toSet()
        } else {
            // Leaves the rows mid-flight, so a test can assert on the "testing"
            // state a real run passes through.
            _testing.value = _testing.value + profileIds
        }
        return startSucceeds
    }

    override fun cancel() {
        cancelCallCount++
        _testing.value = emptySet()
    }

    override fun claimLaunchRun(groupId: Long): Boolean = launchRunClaimed.add(groupId)

    private companion object {
        const val DEFAULT_DELAY_MS = 42
    }
}
