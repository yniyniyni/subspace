// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.feature.profiles.list

import art.yniyniyni.subspace.core.data.ProfileKind
import art.yniyniyni.subspace.core.data.StoredProfile
import art.yniyniyni.subspace.core.model.LatencyOutcome
import art.yniyniyni.subspace.core.model.LatencyResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class LatencySortingTest {
    private fun storedProfile(
        id: Long,
        name: String,
    ): StoredProfile =
        StoredProfile(
            id = id,
            groupId = 1L,
            kind = ProfileKind.TYPED,
            name = name,
            protocol = "vless",
            address = "cdn.example.com",
            port = 443,
            transport = "tcp",
            outbound = null,
            rawJson = null,
            lastConnectedAt = null,
            lastError = null,
        )

    private val a = storedProfile(id = 1, name = "alpha")
    private val b = storedProfile(id = 2, name = "bravo")
    private val c = storedProfile(id = 3, name = "charlie")
    private val d = storedProfile(id = 4, name = "delta")

    @Test
    fun `fastest orders measured ascending, then unmeasured, then failed`() {
        val latencies =
            mapOf(
                1L to LatencyResult.ok(120),
                2L to LatencyResult.failed(LatencyOutcome.UNREACHABLE),
                4L to LatencyResult.ok(30),
            )

        // 4 (30ms), 1 (120ms), then unmeasured 3, then failed 2 last: a server
        // that did not answer is not a fast server.
        listOf(a, b, c, d).sortedFor(SortOrder.Fastest, latencies).map { it.id } shouldContainExactly
            listOf(4L, 1L, 3L, 2L)
    }

    @Test
    fun `unmeasured rows keep their as-listed order among themselves`() {
        val latencies = mapOf(1L to LatencyResult.ok(50))

        // Stable sort: 2, 3, 4 are all unmeasured and keep the provider's order
        // rather than being scrambled.
        listOf(a, b, c, d).sortedFor(SortOrder.Fastest, latencies).map { it.id } shouldContainExactly
            listOf(1L, 2L, 3L, 4L)
    }

    @Test
    fun `fastest with no measurements at all is as-listed`() {
        // The cold-start case, on every launch until ping-on-launch fills it.
        listOf(a, b, c).sortedFor(SortOrder.Fastest, emptyMap()).map { it.id } shouldContainExactly
            listOf(1L, 2L, 3L)
    }

    @Test
    fun `a timed-out row sorts with the failures, not with its elapsed time`() {
        val latencies =
            mapOf(
                1L to LatencyResult.failed(LatencyOutcome.TIMEOUT),
                2L to LatencyResult.ok(500),
            )

        listOf(a, b).sortedFor(SortOrder.Fastest, latencies).map { it.id } shouldContainExactly listOf(2L, 1L)
    }

    @Test
    fun `an unsupported row sorts with the failures`() {
        val latencies =
            mapOf(
                1L to LatencyResult.failed(LatencyOutcome.UNSUPPORTED),
                2L to LatencyResult.ok(900),
            )

        listOf(a, b).sortedFor(SortOrder.Fastest, latencies).map { it.id } shouldContainExactly listOf(2L, 1L)
    }

    @Test
    fun `the other orders ignore latency entirely`() {
        val latencies = mapOf(3L to LatencyResult.ok(1))

        listOf(c, a, b).sortedFor(SortOrder.Alphabetical, latencies).map { it.name } shouldContainExactly
            listOf("alpha", "bravo", "charlie")
    }

    @Test
    fun `the sort directive maps onto the three orders a provider can express`() {
        sortOrderFromDirective("without") shouldBe SortOrder.AsListed
        sortOrderFromDirective("ping") shouldBe SortOrder.Fastest
        sortOrderFromDirective("alphabet") shouldBe SortOrder.Alphabetical
    }

    @Test
    fun `an unrecognised sort directive yields null so the user's own default wins`() {
        // Null rather than a substituted order: a provider sending nonsense must
        // not silently rearrange the list into something nobody chose.
        sortOrderFromDirective("nonsense").shouldBeNull()
        sortOrderFromDirective(null).shouldBeNull()
        sortOrderFromDirective("").shouldBeNull()
    }
}
