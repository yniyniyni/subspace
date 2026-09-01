// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * ARCHITECTURE.md §A.1: `true` or `1` enables; any other non-empty value disables — not "false
 * disables". [RefreshScheduler][space.getsub.sync.RefreshScheduler] (`:app`) had this
 * backwards at both its call sites (`.value != "false"`), which is safe only as long as nothing
 * can ever store a value outside `{"true", "false"}` — true for the provider path
 * ([space.getsub.core.parser.directive.DirectiveValidator] canonicalises to exactly
 * those two strings) but not for [SubscriptionRepository.pin], which took an unvalidated
 * `String` with no call site until Task 15 built one. These pin cases are exactly what
 * `.value != "false"` gets backwards: a pinned `"0"` reads as *enabled*.
 */
class EffectiveValueTest {
    private fun effectiveValue(value: String?) =
        EffectiveValue(
            key = "k",
            value = value,
            providerValue = null,
            isPinned = false,
        )

    @Test
    fun trueEnables() {
        effectiveValue("true").isEnabled shouldBe true
    }

    @Test
    fun oneEnables() {
        effectiveValue("1").isEnabled shouldBe true
    }

    @Test
    fun trueIsCaseInsensitive() {
        effectiveValue("TRUE").isEnabled shouldBe true
    }

    @Test
    fun falseDisables() {
        effectiveValue("false").isEnabled shouldBe false
    }

    // The exact regression: a pinned "0" used to read as enabled under `.value != "false"`.
    @Test
    fun zeroDisablesEvenThoughItIsNotTheStringFalse() {
        effectiveValue("0").isEnabled shouldBe false
    }

    @Test
    fun anyUnrecognisedNonEmptyValueDisables() {
        effectiveValue("no").isEnabled shouldBe false
        effectiveValue("off").isEnabled shouldBe false
        effectiveValue("garbage").isEnabled shouldBe false
    }

    @Test
    fun blankDisables() {
        effectiveValue("").isEnabled shouldBe false
        effectiveValue("   ").isEnabled shouldBe false
    }

    @Test
    fun absentDisables() {
        effectiveValue(null).isEnabled shouldBe false
    }
}
