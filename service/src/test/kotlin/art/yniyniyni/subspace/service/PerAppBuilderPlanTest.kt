// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.PerAppMode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test

/**
 * Covers the one decision that cannot be made wrong twice: a `VpnService.Builder`
 * holds allowed applications or disallowed ones, never both, and mixing them
 * throws `UnsupportedOperationException` at connect time on a device rather than
 * at build time in CI (spec §2.2).
 */
class PerAppBuilderPlanTest {
    @Test
    fun offDisallowsOnlyOurOwnPackage() {
        builderPlan(PerAppResolution.Off) shouldBe BuilderPlan.DisallowOwnOnly
    }

    @Test
    fun aDenyListDisallowsItsPackages() {
        val plan = builderPlan(PerAppResolution.Selected(PerAppMode.DenyList, setOf("com.example.bank")))

        plan shouldBe BuilderPlan.Disallow(setOf("com.example.bank"))
    }

    @Test
    fun anAllowListAllowsItsPackages() {
        val plan = builderPlan(PerAppResolution.Selected(PerAppMode.AllowList, setOf("com.example.browser")))

        plan shouldBe BuilderPlan.Allow(setOf("com.example.browser"))
    }

    // The whole point of the type. If Allow ever also carried packages to
    // disallow, or Disallow carried packages to allow, the Builder would throw.
    @Test
    fun noPlanEverMixesAllowedAndDisallowedPackages() {
        val plans =
            listOf(
                builderPlan(PerAppResolution.Off),
                builderPlan(PerAppResolution.Selected(PerAppMode.DenyList, setOf("a"))),
                builderPlan(PerAppResolution.Selected(PerAppMode.AllowList, setOf("b"))),
            )

        plans.forEach { plan ->
            when (plan) {
                is BuilderPlan.Allow -> plan.packages.isNotEmpty() shouldBe true
                is BuilderPlan.Disallow -> plan.packages.isNotEmpty() shouldBe true
                BuilderPlan.DisallowOwnOnly -> Unit
                null -> error("only EmptyAllowList has no plan")
            }
        }
    }

    // An allow-list plan must never carry our own package: in that mode we are
    // excluded by omission, and adding ourselves would route the app through
    // itself — §5.1's loop by construction.
    @Test
    fun anAllowPlanCarriesOnlyWhatTheResolverPassed() {
        val plan = builderPlan(PerAppResolution.Selected(PerAppMode.AllowList, setOf("com.example.browser")))

        plan.shouldBeInstanceOf<BuilderPlan.Allow>()
        plan.packages.contains("art.yniyniyni.subspace") shouldBe false
    }

    // No plan at all: the start is refused before a Builder is touched.
    @Test
    fun anEmptyAllowListHasNoPlan() {
        builderPlan(PerAppResolution.EmptyAllowList) shouldBe null
    }

    // The second way an allow list can end up allowing nothing, and the one no
    // resolver can see: the packages were selected while installed and are gone
    // by the time the builder is asked for them. AOSP never creates the
    // allowed-applications list if every add throws, and a null list is "tunnel
    // everything" — the exact inversion of what the user configured.
    @Test
    fun anAllowListWhoseEveryPackageIsGoneAppliesNothing() {
        val applied = applyEach(setOf("com.example.gone", "com.example.alsoGone")) { false }

        applied shouldBe PackageApplication(requested = 2, skipped = 2)
        applied.nothingApplied shouldBe true
    }

    @Test
    fun oneSurvivingPackageIsEnoughToApply() {
        val applied =
            applyEach(setOf("com.example.here", "com.example.gone")) { name ->
                name == "com.example.here"
            }

        applied shouldBe PackageApplication(requested = 2, skipped = 1)
        applied.nothingApplied shouldBe false
    }

    // §8's skip-and-continue: a single uninstalled package is counted, not fatal.
    @Test
    fun everyInstalledPackageAppliesWithNothingSkipped() {
        val applied = applyEach(setOf("com.example.a", "com.example.b")) { true }

        applied shouldBe PackageApplication(requested = 2, skipped = 0)
        applied.nothingApplied shouldBe false
    }

    // Defensive: the resolver guarantees a non-empty Selected, but if an empty
    // set ever reached here the allow-list arm must still refuse rather than
    // hand the builder a plan that filters nothing.
    @Test
    fun anEmptySetAppliesNothing() {
        applyEach(emptySet()) { true }.nothingApplied shouldBe true
    }
}
