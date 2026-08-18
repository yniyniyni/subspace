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
}
