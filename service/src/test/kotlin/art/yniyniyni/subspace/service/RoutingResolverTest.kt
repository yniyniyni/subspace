// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class RoutingResolverTest {
    private fun resolver(
        activeId: Long?,
        ruleSet: RoutingRuleSet? = null,
        installed: Set<String> = emptySet(),
        assetDir: File = File("/geo"),
    ) = RoutingResolver(
        activeRuleSetId = { activeId },
        // `id == activeId` rather than unconditionally returning `stored`: a
        // resolver that called loadStored with the wrong id (e.g. a hardcoded
        // 0L) must fail every test below that supplies a ruleSet, not silently
        // pass all of them (code review finding 5, fix round 1).
        loadStored = { id -> ruleSet?.let(::storedRuleSet).takeIf { id == activeId } },
        installedGeoFiles = { directory -> installed.takeIf { directory == assetDir }.orEmpty() },
        assetDirFor = { _, _, _ -> assetDir },
    )

    private fun storedRuleSet(
        ruleSet: RoutingRuleSet,
        generation: Long = 0,
        hasOwnSources: Boolean = false,
    ): StoredRuleSet =
        StoredRuleSet(
            ruleSet = ruleSet,
            sourceKind = null,
            subscriptionId = null,
            lastUpdated = null,
            fingerprint = null,
            geoIpUrl = "https://example.test/geoip.dat".takeIf { hasOwnSources },
            geoSiteUrl = null,
            hasUnappliedDns = false,
            assetGeneration = generation,
            assetState = RuleSetAssetState.None,
            assetFailure = null,
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

    @Test
    fun `an active profile resolves to its own generation directory`() = runTest {
        val expected = File("/geo/sets/7/3")
        val profileRuleSet =
            literalSet.copy(
                id = 7,
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(ips = listOf("geoip:private"))),
            )
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 7L },
                loadStored = {
                    storedRuleSet(
                        ruleSet = profileRuleSet,
                        generation = 3,
                        hasOwnSources = true,
                    )
                },
                installedGeoFiles = { directory ->
                    setOf("geoip.dat").takeIf { directory == expected }.orEmpty()
                },
                assetDirFor = { id, generation, own ->
                    File("/geo/sets/$id/$generation").takeIf { own } ?: File("/geo")
                },
            )

        resolver.resolve().shouldBeInstanceOf<RoutingResolution.Active>().assetDir shouldBe expected
    }

    @Test
    fun `a hand-made set still resolves to the shared root`() = runTest {
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 7L },
                loadStored = {
                    storedRuleSet(
                        ruleSet = literalSet.copy(id = 7),
                        generation = 0,
                        hasOwnSources = false,
                    )
                },
                installedGeoFiles = { setOf("geoip.dat") },
                assetDirFor = { _, _, own -> if (own) File("/geo/sets/x") else File("/geo") },
            )

        resolver.resolve().shouldBeInstanceOf<RoutingResolution.Active>().assetDir shouldBe File("/geo")
    }

    @Test
    fun `a profile referencing an unsuppliable ext file is blocked with the name shown`() = runTest {
        val extSet =
            literalSet.copy(
                id = 7,
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf("ext:custom.dat:cn"))),
            )
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 7L },
                loadStored = {
                    storedRuleSet(
                        ruleSet = extSet,
                        generation = 1,
                        hasOwnSources = true,
                    )
                },
                installedGeoFiles = { setOf("geoip.dat", "geosite.dat") },
                assetDirFor = { id, generation, _ -> File("/geo/sets/$id/$generation") },
            )

        resolver.resolve().shouldBeInstanceOf<RoutingResolution.MissingGeoData>().missing shouldBe
            setOf("custom.dat")
    }
}
