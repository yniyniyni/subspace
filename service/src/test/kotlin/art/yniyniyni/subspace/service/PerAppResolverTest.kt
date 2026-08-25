// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package art.yniyniyni.subspace.service

import art.yniyniyni.subspace.core.model.PerAppMode
import art.yniyniyni.subspace.core.model.PerAppSelection
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PerAppResolverTest {
    private val ours = "art.yniyniyni.subspace"

    private fun resolver(selection: PerAppSelection) =
        PerAppResolver(selection = { selection }, ownPackage = { ours })

    @Test
    fun offResolvesToOff() = runTest {
        resolver(PerAppSelection.OFF).resolve() shouldBe PerAppResolution.Off
    }

    @Test
    fun aDenyListResolvesToItsPackages() = runTest {
        val selection = PerAppSelection(PerAppMode.DenyList, setOf("com.example.bank"))

        resolver(selection).resolve() shouldBe
            PerAppResolution.Selected(PerAppMode.DenyList, setOf("com.example.bank"))
    }

    @Test
    fun anAllowListResolvesToItsPackages() = runTest {
        val selection = PerAppSelection(PerAppMode.AllowList, setOf("com.example.browser"))

        resolver(selection).resolve() shouldBe
            PerAppResolution.Selected(PerAppMode.AllowList, setOf("com.example.browser"))
    }

    // §10.1's signature, produced by configuration rather than by a bug: an
    // allow-list with nothing in it builds a TUN no app may use. Connected, no
    // traffic, no error. It must be refused, not started.
    @Test
    fun anEmptyAllowListIsItsOwnResolutionNotOff() = runTest {
        val selection = PerAppSelection(PerAppMode.AllowList, emptySet())

        resolver(selection).resolve() shouldBe PerAppResolution.EmptyAllowList
    }

    // §8: never route the app through itself. In allow-list mode nothing calls
    // addDisallowedApplication (that would throw — see Task 6), so this filter is
    // the only thing standing between a stored own-package entry and a §5.1 loop.
    @Test
    fun ourOwnPackageIsStrippedFromAnAllowList() = runTest {
        val selection = PerAppSelection(PerAppMode.AllowList, setOf(ours, "com.example.browser"))

        resolver(selection).resolve() shouldBe
            PerAppResolution.Selected(PerAppMode.AllowList, setOf("com.example.browser"))
    }

    @Test
    fun ourOwnPackageIsStrippedFromADenyListToo() = runTest {
        val selection = PerAppSelection(PerAppMode.DenyList, setOf(ours, "com.example.bank"))

        resolver(selection).resolve() shouldBe
            PerAppResolution.Selected(PerAppMode.DenyList, setOf("com.example.bank"))
    }

    // Stripping must not turn a one-element allow list into a silently-empty one
    // that still starts. If we were the only entry, there is nothing left to allow.
    @Test
    fun anAllowListOfOnlyOurOwnPackageIsEmptyNotSelected() = runTest {
        val selection = PerAppSelection(PerAppMode.AllowList, setOf(ours))

        resolver(selection).resolve() shouldBe PerAppResolution.EmptyAllowList
    }

    // The deny-list mirror: stripping us leaves nothing to deny, which is Off.
    // PerAppRepository collapses this case already; the resolver must not
    // reintroduce it by stripping after that collapse ran.
    @Test
    fun aDenyListOfOnlyOurOwnPackageIsOff() = runTest {
        val selection = PerAppSelection(PerAppMode.DenyList, setOf(ours))

        resolver(selection).resolve() shouldBe PerAppResolution.Off
    }
}
