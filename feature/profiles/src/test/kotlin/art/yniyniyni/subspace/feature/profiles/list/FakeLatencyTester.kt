// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.model.LatencyResult
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

    var resultFor: (Long) -> LatencyResult = { LatencyResult.ok(DEFAULT_DELAY_MS) }

    var testedIds: List<Long> = emptyList()
        private set
    var testCallCount: Int = 0
        private set
    var cancelCallCount: Int = 0
        private set

    private val launchRunClaimed = mutableSetOf<Long>()

    override suspend fun test(profileIds: List<Long>) {
        if (profileIds.isEmpty()) return
        testCallCount++
        testedIds = profileIds
        if (autoComplete) {
            _results.value = _results.value + profileIds.associateWith { id -> resultFor(id) }
            _testing.value = _testing.value - profileIds.toSet()
        } else {
            // Leaves the rows mid-flight, so a test can assert on the "testing"
            // state a real run passes through.
            _testing.value = _testing.value + profileIds
        }
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
