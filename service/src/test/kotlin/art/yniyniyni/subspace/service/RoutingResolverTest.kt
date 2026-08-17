// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoutingResolverTest {
    private fun resolver(
        activeId: Long?,
        ruleSet: RoutingRuleSet? = null,
        installed: Set<String> = emptySet(),
    ) = RoutingResolver(
        activeRuleSetId = { activeId },
        // `id == activeId` rather than unconditionally returning `ruleSet`: a
        // resolver that called loadRuleSet with the wrong id (e.g. a hardcoded
        // 0L) must fail every test below that supplies a ruleSet, not silently
        // pass all of them (code review finding 5, fix round 1).
        loadRuleSet = { id -> ruleSet.takeIf { id == activeId } },
        installedGeoFiles = { installed },
    )

    private val geoSet =
        RoutingRuleSet(
            id = 1,
            name = "geo",
            buckets =
            mapOf(
                RouteOutcome.BLOCK to RuleBucket(sites = listOf("geosite:category-ads-all")),
                RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:ru")),
            ),
        )

    private val literalSet =
        RoutingRuleSet(
            id = 2,
            name = "lan",
            buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("10.0.0.0/8"))),
        )

    @Test
    fun `no active rule set resolves to off`() = runTest {
        resolver(activeId = null).resolve() shouldBe RoutingResolution.Off
    }

    // A rule set that was deleted while it was active must not wedge the tunnel.
    @Test
    fun `an active id that no longer resolves is off, not an error`() = runTest {
        resolver(activeId = 99, ruleSet = null).resolve() shouldBe RoutingResolution.Off
    }

    @Test
    fun `a literal-only rule set is active with no geo files installed`() = runTest {
        val resolution = resolver(activeId = 2, ruleSet = literalSet, installed = emptySet()).resolve()

        resolution.shouldBeInstanceOf<RoutingResolution.Active>()
        resolution.ruleSet shouldBe literalSet
    }

    @Test
    fun `a geo rule set is active when every referenced file is installed`() = runTest {
        val resolution =
            resolver(
                activeId = 1,
                ruleSet = geoSet,
                installed = setOf("geosite.dat", "geoip.dat"),
            ).resolve()

        resolution.shouldBeInstanceOf<RoutingResolution.Active>()
    }

    // The §10.4 distinction: naming the missing files is what tells the user to
    // re-download rather than to go looking for a broken server.
    @Test
    fun `a partially satisfied geo rule set names exactly what is missing`() = runTest {
        val resolution =
            resolver(activeId = 1, ruleSet = geoSet, installed = setOf("geosite.dat")).resolve()

        resolution.shouldBeInstanceOf<RoutingResolution.MissingGeoData>()
        resolution.missing shouldBe setOf("geoip.dat")
    }

    @Test
    fun `a geo rule set with nothing installed reports both files`() = runTest {
        val resolution = resolver(activeId = 1, ruleSet = geoSet, installed = emptySet()).resolve()

        resolution.shouldBeInstanceOf<RoutingResolution.MissingGeoData>()
        resolution.missing shouldBe setOf("geosite.dat", "geoip.dat")
    }
}
