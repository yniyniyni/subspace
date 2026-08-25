// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.data.ResolvedAssetUse
import art.yniyniyni.subspace.core.data.RuleSetAssetScope
import art.yniyniyni.subspace.core.data.StoredRuleSet
import art.yniyniyni.subspace.core.model.DnsResolver
import art.yniyniyni.subspace.core.model.DnsTransport
import art.yniyniyni.subspace.core.model.ProfileDns
import art.yniyniyni.subspace.core.model.RouteOutcome
import art.yniyniyni.subspace.core.model.RoutingRuleSet
import art.yniyniyni.subspace.core.model.RuleBucket
import art.yniyniyni.subspace.core.model.RuleSetAssetState
import io.kotest.assertions.throwables.shouldThrow
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
        assetScope = FakeAssetScope(directoryFor = { _, _, _ -> assetDir }),
    )

    private suspend fun RoutingResolver.resolveForTest(): RoutingResolution = withResolution { it }

    private fun storedRuleSet(
        ruleSet: RoutingRuleSet,
        generation: Long = 0,
        hasOwnSources: Boolean = false,
        dns: ProfileDns? = null,
    ): StoredRuleSet =
        StoredRuleSet(
            ruleSet = ruleSet,
            sourceKind = null,
            subscriptionId = null,
            lastUpdated = null,
            fingerprint = null,
            geoIpUrl = "https://example.test/geoip.dat".takeIf { hasOwnSources },
            geoSiteUrl = null,
            hasDns = dns != null,
            assetGeneration = generation,
            assetState = RuleSetAssetState.None,
            assetFailure = null,
            dns = dns,
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
        resolver(activeId = null).resolveForTest() shouldBe RoutingResolution.Off
    }

    // A rule set that was deleted while it was active must not wedge the tunnel.
    @Test
    fun `an active id that no longer resolves is off, not an error`() = runTest {
        resolver(activeId = 99, ruleSet = null).resolveForTest() shouldBe RoutingResolution.Off
    }

    @Test
    fun `a literal-only rule set is active with no geo files installed`() = runTest {
        val resolution = resolver(activeId = 2, ruleSet = literalSet, installed = emptySet()).resolveForTest()

        resolution.shouldBeInstanceOf<RoutingResolution.Active>()
        resolution.ruleSet shouldBe literalSet
    }

    // Fix round 1, Finding 3: nothing previously asserted that Active actually
    // carries the stored row's DNS block through rather than defaulting to
    // null — every earlier test in this file left `dns` unset. This is the
    // milestone's specific failure mode: a stored DNS block that never reaches
    // the tunnel while every test still passes, because nothing checked this
    // one hop of the chain.
    @Test
    fun `an active resolution carries the stored row's dns block through`() = runTest {
        val dns = ProfileDns(remote = DnsResolver(DnsTransport.DOU, ip = "8.8.8.8"))
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 2L },
                loadStored = { storedRuleSet(literalSet, dns = dns) },
                installedGeoFiles = { emptySet() },
                assetScope = FakeAssetScope(directoryFor = { _, _, _ -> File("/geo") }),
            )

        resolver.resolveForTest().shouldBeInstanceOf<RoutingResolution.Active>().dns shouldBe dns
    }

    @Test
    fun `a geo rule set is active when every referenced file is installed`() = runTest {
        val resolution =
            resolver(
                activeId = 1,
                ruleSet = geoSet,
                installed = setOf("geosite.dat", "geoip.dat"),
            ).resolveForTest()

        resolution.shouldBeInstanceOf<RoutingResolution.Active>()
    }

    // The §10.4 distinction: naming the missing files is what tells the user to
    // re-download rather than to go looking for a broken server.
    @Test
    fun `a partially satisfied geo rule set names exactly what is missing`() = runTest {
        val resolution =
            resolver(activeId = 1, ruleSet = geoSet, installed = setOf("geosite.dat")).resolveForTest()

        resolution.shouldBeInstanceOf<RoutingResolution.MissingGeoData>()
        resolution.missing shouldBe setOf("geoip.dat")
    }

    @Test
    fun `a geo rule set with nothing installed reports both files`() = runTest {
        val resolution = resolver(activeId = 1, ruleSet = geoSet, installed = emptySet()).resolveForTest()

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
        val assetScope =
            FakeAssetScope(directoryFor = { id, generation, own ->
                File("/geo/sets/$id/$generation").takeIf { own } ?: File("/geo")
            })
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
                assetScope = assetScope,
            )

        resolver.withResolution { resolution ->
            assetScope.inUse shouldBe true
            resolution.shouldBeInstanceOf<RoutingResolution.Active>().assetDir shouldBe expected
        }
        assetScope.inUse shouldBe false
    }

    // Regression, P1: a valid imported profile may carry Geoipurl/Geositeurl
    // while its rules are literal-only. The importer treats that as needing no
    // geo files and commits generation 0; inferring ownership from URL presence
    // then sent the service to generationDir(id, 0), whose positive-generation
    // guard throws during tunnel startup.
    @Test
    fun `a literal-only profile carrying geo urls resolves to the shared root`() = runTest {
        val assetScope =
            FakeAssetScope(
                directoryFor = { id, generation, own ->
                    if (own) File("/geo/sets/$id/$generation") else File("/geo")
                },
            )
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 7L },
                loadStored = {
                    storedRuleSet(
                        ruleSet = literalSet.copy(id = 7),
                        generation = 0,
                        hasOwnSources = true,
                    )
                },
                installedGeoFiles = { emptySet() },
                assetScope = assetScope,
            )

        val resolution = resolver.resolveForTest()

        resolution.shouldBeInstanceOf<RoutingResolution.Active>().assetDir shouldBe File("/geo")
    }

    @Test
    fun `a hand-made set still resolves to the shared root`() = runTest {
        val assetScope =
            FakeAssetScope(
                directoryFor = { _, _, own -> if (own) File("/geo/sets/x") else File("/geo") },
            )
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
                assetScope = assetScope,
            )

        resolver.resolveForTest().shouldBeInstanceOf<RoutingResolution.Active>().assetDir shouldBe File("/geo")
    }

    @Test
    fun `a profile referencing an unsuppliable ext file is blocked with the name shown`() = runTest {
        val extSet =
            literalSet.copy(
                id = 7,
                buckets = mapOf(RouteOutcome.DIRECT to RuleBucket(sites = listOf("ext:custom.dat:cn"))),
            )
        val assetScope =
            FakeAssetScope(
                directoryFor = { id, generation, _ -> File("/geo/sets/$id/$generation") },
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
                assetScope = assetScope,
            )

        resolver.resolveForTest().shouldBeInstanceOf<RoutingResolution.MissingGeoData>().missing shouldBe
            setOf("custom.dat")
    }

    @Test
    fun `a generation update during acquisition retries inside one resolution scope`() = runTest {
        // geoSet, not literalSet: owning a generation now means the published
        // rules actually require a geo file, so a literal-only set — even one
        // carrying a geo URL — resolves to the shared root and never takes a
        // lease. That distinction is the defect this predicate was changed for.
        val generationOne = storedRuleSet(geoSet.copy(id = 7), generation = 1, hasOwnSources = true)
        val generationTwo = storedRuleSet(geoSet.copy(id = 7), generation = 2, hasOwnSources = true)
        val assetScope =
            FakeAssetScope(
                directoryFor = { id, generation, _ -> File("/geo/sets/$id/$generation") },
            )
        var callbackCount = 0
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 7L },
                loadStored = {
                    when (assetScope.calls) {
                        0 -> generationOne
                        else -> generationTwo
                    }
                },
                // Present, because this test is about the lease retry and the
                // directory it settles on, not the missing-file gate.
                installedGeoFiles = { setOf("geosite.dat", "geoip.dat") },
                assetScope = assetScope,
            )

        resolver.withResolution { resolution ->
            callbackCount += 1
            resolution.shouldBeInstanceOf<RoutingResolution.Active>().assetDir shouldBe File("/geo/sets/7/2")
        }

        assetScope.calls shouldBe 2
        callbackCount shouldBe 1
    }

    @Test
    fun `continuous generation churn stops after the bounded retry count`() = runTest {
        val assetScope = FakeAssetScope(
            directoryFor = { id, generation, _ -> File("/geo/sets/$id/$generation") },
            unavailableAttempts = Int.MAX_VALUE,
        )
        val resolver =
            RoutingResolver(
                activeRuleSetId = { 7L },
                loadStored = { storedRuleSet(geoSet.copy(id = 7), generation = 1, hasOwnSources = true) },
                installedGeoFiles = { emptySet() },
                assetScope = assetScope,
            )

        shouldThrow<RoutingGenerationChurnException> {
            resolver.withResolution { error("must not expose an unleased generation") }
        }

        assetScope.calls shouldBe 3
    }

    private class FakeAssetScope(
        private val directoryFor: (Long, Long, Boolean) -> File,
        private val unavailableAttempts: Int = 0,
    ) : RuleSetAssetScope {
        var calls: Int = 0
            private set
        var inUse: Boolean = false
            private set

        override suspend fun <T> withResolvedAssetDir(
            setId: Long,
            generation: Long,
            hasOwnSources: Boolean,
            block: suspend (File) -> T,
        ): ResolvedAssetUse<T> {
            calls += 1
            if (calls <= unavailableAttempts) return ResolvedAssetUse.GenerationUnavailable
            inUse = true
            return try {
                ResolvedAssetUse.Used(block(directoryFor(setId, generation, hasOwnSources)))
            } finally {
                inUse = false
            }
        }
    }
}
